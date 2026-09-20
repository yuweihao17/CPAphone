package com.cpaphone.engine

import com.cpaphone.core.config.YamlConfigTransformer
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.RoutingStrategyType
import com.cpaphone.core.oauth.PROVIDER_SPECS
import com.cpaphone.core.session.OAuthFlowStatus
import com.cpaphone.core.session.OAuthSessionManager
import org.junit.Assert.*
import org.junit.Test

class ManagementApiTest {

    @Test
    fun testYamlConfigTransformerExportAndParse() {
        val cred1 = AuthCredential(
            id = "c1",
            alias = "Claude-Pro",
            provider = ProviderType.CLAUDE,
            authType = AuthType.OAUTH,
            weight = 5,
            status = CredentialStatus.ACTIVE,
            customBaseUrl = "https://custom.api.com"
        )

        val exportedYaml = YamlConfigTransformer.exportToYaml(
            port = 8317,
            allowLan = true,
            strategy = RoutingStrategyType.WEIGHTED_ROUND_ROBIN,
            safeMode = true,
            cloaking = true,
            proxyUrl = "socks5://127.0.0.1:1080",
            credentials = listOf(cred1)
        )

        assertNotNull(exportedYaml)
        assertTrue(exportedYaml.contains("port: 8317"))
        assertTrue(exportedYaml.contains("host: \"0.0.0.0\""))
        assertTrue(exportedYaml.contains("proxy-url: \"socks5://127.0.0.1:1080\""))
        assertTrue(exportedYaml.contains("weighted-round-robin"))
        assertTrue(exportedYaml.contains("alias: \"Claude-Pro\""))

        // 测试轻量反向解析
        val parsed = YamlConfigTransformer.parseYamlBasic(exportedYaml)
        assertEquals(8317, parsed.port)
        assertTrue(parsed.allowLan)
        assertEquals(RoutingStrategyType.WEIGHTED_ROUND_ROBIN, parsed.strategy)
        assertEquals("socks5://127.0.0.1:1080", parsed.proxyUrl)
        assertTrue(parsed.safeMode)
    }

    @Test
    fun testOAuthSessionLifecycle() {
        val manager = OAuthSessionManager()
        val spec = PROVIDER_SPECS[ProviderType.CLAUDE]!!

        // 1. 发起 Claude 授权会话（真实 PKCE 授权 URL）
        val session = manager.startSession(ProviderType.CLAUDE, spec)
        val authUrl = session.authorizeUrl ?: ""
        assertTrue(session.state.isNotBlank())
        assertTrue(authUrl.contains("claude.ai/oauth/authorize"))
        assertTrue(authUrl.contains(session.state))
        assertTrue(authUrl.contains("code_challenge"))
        assertTrue(authUrl.contains("code_challenge_method=S256"))
        assertTrue(authUrl.contains("54545"))

        // 2. 轮询状态，初始状态应为 WAIT
        val initialSession = manager.pollStatus(session.state)
        assertNotNull(initialSession)
        assertEquals(OAuthFlowStatus.WAIT, initialSession?.status)

        // 3. 模拟接收回调 Code
        assertTrue(manager.completeWithCode(session.state, "code_sample_12345", null))
        assertEquals("code_sample_12345", manager.peek(session.state)?.authCode)

        // 4. 完成落库后状态变为 OK
        manager.completeSession(session.state, "Claude-test", "user@test.com")
        val completedSession = manager.peek(session.state)
        assertEquals(OAuthFlowStatus.OK, completedSession?.status)
        assertEquals("Claude-test", completedSession?.resultAlias)

        // 5. 取消/删除会话
        val cancelled = manager.cancelSession(session.state)
        assertTrue(cancelled)
        assertNull(manager.peek(session.state))
    }
}
