package com.cpaphone.core

import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.oauth.OAuthFlowKind
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.core.oauth.Pkce
import com.cpaphone.core.oauth.generateOAuthState
import com.cpaphone.core.oauth.parseJwtEmail
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OAuth 登录基础设施测试：PKCE 向量、state 格式、JWT email 解析与 5 家提供商规格完整性
 */
class PkceAndSpecTest {

    @Test
    fun `pkce challenge matches RFC 7636 appendix B vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, Pkce.generateChallenge(verifier))
    }

    @Test
    fun `generated verifier is 128 chars base64url without padding`() {
        val verifier = Pkce.generateVerifier()
        assertEquals(128, verifier.length)
        assertFalse(verifier.contains('+'))
        assertFalse(verifier.contains('/'))
        assertFalse(verifier.contains('='))
        // 两次生成不得重复
        assertTrue(Pkce.generateVerifier() != verifier)
    }

    @Test
    fun `generated state is 32 lowercase hex chars`() {
        val state = generateOAuthState()
        assertEquals(32, state.length)
        assertTrue(state.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `parseJwtEmail extracts email claim`() {
        val payloadJson = """{"sub":"123","email":"user@test.com"}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
        val idToken = "eyJhbGciOiJSUzI1NiJ9.$payload.sig"
        assertEquals("user@test.com", parseJwtEmail(idToken))
    }

    @Test
    fun `parseJwtEmail returns null on malformed token`() {
        assertNull(parseJwtEmail("not-a-jwt"))
        assertNull(parseJwtEmail("a.$$$$.d"))
    }

    @Test
    fun `provider specs completeness for all five login providers`() {
        val claude = PROVIDER_SPECS[ProviderType.CLAUDE]
        assertNotNull(claude)
        assertEquals(OAuthFlowKind.CODE_PKCE, claude.flowKind)
        assertEquals(54545, claude.callbackPort)
        assertTrue(claude.redirectUri!!.contains("54545"))
        assertTrue(claude.authorizeUrl!!.startsWith("https://claude.ai/"))

        val codex = PROVIDER_SPECS[ProviderType.OPENAI_CODEX]
        assertNotNull(codex)
        assertEquals(OAuthFlowKind.CODE_PKCE, codex.flowKind)
        assertEquals(1455, codex.callbackPort)
        assertTrue(codex.clientId.startsWith("app_"))

        val antigravity = PROVIDER_SPECS[ProviderType.ANTIGRAVITY]
        assertNotNull(antigravity)
        assertEquals(OAuthFlowKind.CODE_SECRET, antigravity.flowKind)
        assertEquals(51121, antigravity.callbackPort)
        assertNotNull(antigravity.clientSecret)

        val kimi = PROVIDER_SPECS[ProviderType.KIMI]
        assertNotNull(kimi)
        assertEquals(OAuthFlowKind.DEVICE_CODE, kimi.flowKind)
        assertNotNull(kimi.deviceAuthUrl)
        assertNull(kimi.redirectUri)

        val xai = PROVIDER_SPECS[ProviderType.XAI]
        assertNotNull(xai)
        assertEquals(OAuthFlowKind.DEVICE_CODE, xai.flowKind)
        assertNotNull(xai.oidcDiscoveryUrl)

        PROVIDER_SPECS.values.forEach { spec ->
            assertTrue(spec.tokenUrl.startsWith("https://"), "tokenUrl must be https: ${spec.tokenUrl}")
            assertTrue(spec.clientId.isNotBlank())
        }
    }
}
