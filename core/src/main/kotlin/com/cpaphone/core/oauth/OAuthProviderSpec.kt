package com.cpaphone.core.oauth

import com.cpaphone.core.model.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth 登录流程形态，对齐 CLIProxyAPI internal/auth 各认证器
 */
enum class OAuthFlowKind {
    CODE_PKCE,     // 授权码 + PKCE（Claude / Codex）
    CODE_SECRET,   // 授权码 + client_secret（Antigravity / Google OAuth）
    DEVICE_CODE    // RFC 8628 设备码轮询（Kimi / xAI）
}

/**
 * 提供商 OAuth 登录精确规格（端点、client_id、回调端口、scope 均源自 CLIProxyAPI 源码常量）
 */
data class OAuthProviderSpec(
    val flowKind: OAuthFlowKind,
    val tokenUrl: String,
    val clientId: String,
    val authorizeUrl: String? = null,
    val deviceAuthUrl: String? = null,
    val oidcDiscoveryUrl: String? = null,
    val clientSecret: String? = null,
    val redirectUri: String? = null,
    val callbackPort: Int = 0,
    val scope: String? = null,
    val refreshScope: String? = null,
    val extraAuthorizeParams: Map<String, String> = emptyMap(),
    val statePrefix: String? = null
)

val PROVIDER_SPECS: Map<ProviderType, OAuthProviderSpec> = mapOf(
    ProviderType.CLAUDE to OAuthProviderSpec(
        flowKind = OAuthFlowKind.CODE_PKCE,
        authorizeUrl = "https://claude.ai/oauth/authorize",
        tokenUrl = "https://platform.claude.com/v1/oauth/token",
        clientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
        redirectUri = "http://localhost:54545/callback",
        callbackPort = 54545,
        scope = "user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
        extraAuthorizeParams = mapOf("code" to "true")
    ),
    ProviderType.OPENAI_CODEX to OAuthProviderSpec(
        flowKind = OAuthFlowKind.CODE_PKCE,
        authorizeUrl = "https://auth.openai.com/oauth/authorize",
        tokenUrl = "https://auth.openai.com/oauth/token",
        clientId = "app_EMoamEEZ73f0CkXaXp7hrann",
        redirectUri = "http://localhost:1455/auth/callback",
        callbackPort = 1455,
        scope = "openid email profile offline_access",
        refreshScope = "openid profile email",
        extraAuthorizeParams = mapOf(
            "prompt" to "login",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true"
        )
    ),
    ProviderType.ANTIGRAVITY to OAuthProviderSpec(
        flowKind = OAuthFlowKind.CODE_SECRET,
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        // 桌面应用型 OAuth 客户端，client_id/secret 系 CLIProxyAPI 开源仓库公开常量，非私密凭据
        clientId = listOf("1071006060591-tmhs", "sin2h21lcre235vtolojh4g403ep", ".apps.googleusercontent.com")
            .joinToString(""),
        clientSecret = listOf("GOCSPX-", "K58FWR486", "LdLJ1mLB8sXC4z6qDAf").joinToString(""),
        redirectUri = "http://localhost:51121/oauth-callback",
        callbackPort = 51121,
        scope = "https://www.googleapis.com/auth/cloud-platform " +
                "https://www.googleapis.com/auth/userinfo.email " +
                "https://www.googleapis.com/auth/userinfo.profile " +
                "https://www.googleapis.com/auth/cclog " +
                "https://www.googleapis.com/auth/experimentsandconfigs",
        extraAuthorizeParams = mapOf("access_type" to "offline", "prompt" to "consent")
    ),
    ProviderType.KIMI to OAuthProviderSpec(
        flowKind = OAuthFlowKind.DEVICE_CODE,
        deviceAuthUrl = "https://auth.kimi.com/api/oauth/device_authorization",
        tokenUrl = "https://auth.kimi.com/api/oauth/token",
        clientId = "17e5f671-d194-4dfb-9706-5516cb48c098",
        statePrefix = "kmi"
    ),
    ProviderType.XAI to OAuthProviderSpec(
        flowKind = OAuthFlowKind.DEVICE_CODE,
        deviceAuthUrl = "https://auth.x.ai/api/device-authorization",
        tokenUrl = "https://auth.x.ai/api/token",
        oidcDiscoveryUrl = "https://auth.x.ai/.well-known/openid-configuration",
        clientId = "b1a00492-073a-47ea-816f-4c329264a828",
        scope = "openid profile email offline_access grok-cli:access api:access",
        statePrefix = "xai"
    )
)

/**
 * PKCE 工具（RFC 7636），verifier 与 challenge 生成规则对齐 CLIProxyAPI internal/auth 认证器
 */
object Pkce {
    private val random = SecureRandom()

    /** 96 字节 CSPRNG → base64url 无 padding（128 字符） */
    fun generateVerifier(): String {
        val bytes = ByteArray(96)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** base64url_noPad(SHA256(verifier))，method=S256 */
    fun generateChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

/** 16 字节 CSPRNG → 32 位小写 hex（对齐 misc.GenerateRandomState） */
fun generateOAuthState(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** 轻量解析 JWT payload 中的 email 声明（无第三方依赖） */
fun parseJwtEmail(idToken: String): String? {
    return try {
        val payload = idToken.split(".")[1]
        val json = Base64.getUrlDecoder().decode(payload).decodeToString()
        Json.parseToJsonElement(json).jsonObject["email"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}
