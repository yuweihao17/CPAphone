package com.cpaphone.core

import com.cpaphone.core.disguise.ClientCloakInterceptor
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.routing.FillFirstLoadBalancer
import com.cpaphone.core.routing.RoundRobinLoadBalancer
import com.cpaphone.core.routing.SmoothWeightedRoundRobinLoadBalancer
import com.cpaphone.core.session.CooldownManager
import com.cpaphone.core.session.SessionAffinityManager
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.core.translator.StreamChunkTranslator
import com.cpaphone.core.translator.UnifiedRole
import org.junit.Assert.*
import org.junit.Test

class CoreDomainTest {

    @Test
    fun testRoundRobinLoadBalancer() {
        val cred1 = createCredential("c1", 1)
        val cred2 = createCredential("c2", 1)
        val balancer = RoundRobinLoadBalancer()

        val list = listOf(cred1, cred2)
        val first = balancer.select(list)
        val second = balancer.select(list)
        val third = balancer.select(list)

        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first?.id, second?.id)
        assertEquals(first?.id, third?.id)
    }

    @Test
    fun testSmoothWeightedRoundRobinLoadBalancer() {
        // A 权重 5，B 权重 1
        val credA = createCredential("A", 5)
        val credB = createCredential("B", 1)
        val balancer = SmoothWeightedRoundRobinLoadBalancer()

        val list = listOf(credA, credB)
        val results = mutableListOf<String>()
        for (i in 0 until 6) {
            results.add(balancer.select(list)!!.id)
        }

        val countA = results.count { it == "A" }
        val countB = results.count { it == "B" }
        assertEquals(5, countA)
        assertEquals(1, countB)
    }

    @Test
    fun testFillFirstLoadBalancer() {
        val cred1 = createCredential("c1", 1)
        val cred2 = createCredential("c2", 1)
        val balancer = FillFirstLoadBalancer()

        assertEquals("c1", balancer.select(listOf(cred1, cred2))?.id)
        assertEquals("c1", balancer.select(listOf(cred1, cred2))?.id)
    }

    @Test
    fun testPerModelCooldownIsolation() {
        val cooldown = CooldownManager(defaultCooldownMs = 1000)
        assertFalse(cooldown.isCooling("c1", "claude-3-7-sonnet"))

        // 仅对 c1 凭据下的 3-7-sonnet 触发局部模型冷却
        cooldown.triggerModelCooldown("c1", "claude-3-7-sonnet", durationMs = 2000)

        // 验证：3-7-sonnet 处于冷却
        assertTrue(cooldown.isCooling("c1", "claude-3-7-sonnet"))
        // 验证：同一凭据下的 3-5-haiku 依然健康可用！
        assertFalse(cooldown.isCooling("c1", "claude-3-5-haiku"))
        // 验证：无模型指定时不受单模型局部冷却影响
        assertFalse(cooldown.isCooling("c1"))
    }

    @Test
    fun testSessionAffinityManager() {
        val affinity = SessionAffinityManager(defaultTtlMs = 1000)
        val cred1 = createCredential("c1", 1)
        val cred2 = createCredential("c2", 1)
        val candidates = listOf(cred1, cred2)

        affinity.bind("session-123", "c2")
        val selected = affinity.getAffinity("session-123", candidates)

        assertNotNull(selected)
        assertEquals("c2", selected?.id)
    }

    @Test
    fun testProtocolTranslatorBidirectional() {
        val openAiJson = """
            {
                "model": "gpt-4o",
                "messages": [
                    {"role": "system", "content": "You are a helpful coding assistant."},
                    {"role": "user", "content": "Hello, write a quicksort."}
                ],
                "stream": true,
                "temperature": 0.7
            }
        """.trimIndent()

        val parsedFromOpenAi = ProtocolTranslatorEngine.parseOpenAiChatRequest(openAiJson)
        assertEquals("gpt-4o", parsedFromOpenAi.model)
        assertEquals("You are a helpful coding assistant.", parsedFromOpenAi.systemPrompt)
        assertEquals("Hello, write a quicksort.", parsedFromOpenAi.messages[0].extractPlainText())

        // 转为 Claude 格式
        val claudeJson = ProtocolTranslatorEngine.toClaudeMessagesJson(parsedFromOpenAi, "claude-3-5-sonnet")
        assertTrue(claudeJson.contains("claude-3-5-sonnet"))
        assertTrue(claudeJson.contains("You are a helpful coding assistant."))

        // 反向从 Claude 格式解析为 UnifiedChatRequest
        val parsedFromClaude = ProtocolTranslatorEngine.parseClaudeMessagesRequest(claudeJson)
        assertEquals("claude-3-5-sonnet", parsedFromClaude.model)
        assertEquals("You are a helpful coding assistant.", parsedFromClaude.systemPrompt)
        assertEquals(UnifiedRole.USER, parsedFromClaude.messages[0].role)
        assertEquals("Hello, write a quicksort.", parsedFromClaude.messages[0].extractPlainText())

        // 再次转回 OpenAI 格式
        val backToOpenAi = ProtocolTranslatorEngine.toOpenAiChatJson(parsedFromClaude, "gpt-4o-reverted")
        assertTrue(backToOpenAi.contains("gpt-4o-reverted"))
        assertTrue(backToOpenAi.contains("Hello, write a quicksort."))
    }

    @Test
    fun testStreamChunkTranslator() {
        // 测试 Claude 思考块流式数据转译
        val claudeThinkingLine = """
            data: {"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "Let me think about quicksort"}}
        """.trimIndent()

        val openAiThinkingChunk = StreamChunkTranslator.translateClaudeToOpenAiChunk(
            claudeThinkingLine,
            modelName = "claude-3-7-sonnet"
        )
        assertNotNull(openAiThinkingChunk)
        assertTrue(openAiThinkingChunk!!.contains("reasoning_content"))
        assertTrue(openAiThinkingChunk.contains("Let me think about quicksort"))

        // 测试 Claude 文本块流式数据转译
        val claudeTextLine = """
            data: {"type": "content_block_delta", "index": 1, "delta": {"type": "text_delta", "text": "Here is the code."}}
        """.trimIndent()

        val openAiTextChunk = StreamChunkTranslator.translateClaudeToOpenAiChunk(
            claudeTextLine,
            modelName = "claude-3-7-sonnet"
        )
        assertNotNull(openAiTextChunk)
        assertTrue(openAiTextChunk!!.contains("\"content\":\"Here is the code.\""))

        // 测试结束帧转译
        val finishLine = """
            data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}}
        """.trimIndent()

        val finishChunk = StreamChunkTranslator.translateClaudeToOpenAiChunk(
            finishLine,
            modelName = "claude-3-7-sonnet"
        )
        assertNotNull(finishChunk)
        assertTrue(finishChunk!!.contains("\"finish_reason\":\"stop\""))
    }

    @Test
    fun testClientCloakInterceptor() {
        val rawHeaders = mapOf(
            "X-Real-IP" to "192.168.1.50",
            "Authorization" to "Bearer test-key"
        )

        val claudeHeaders = ClientCloakInterceptor.applyHeaders(ProviderType.CLAUDE, rawHeaders, enableCloak = true)
        // 验证过滤内部敏感头
        assertFalse(claudeHeaders.containsKey("X-Real-IP"))
        // 验证伪装官方 User-Agent 与版本头
        assertTrue(claudeHeaders.containsKey("User-Agent"))
        assertTrue(claudeHeaders["User-Agent"]!!.contains("claude-cli"))
        assertEquals("2023-06-01", claudeHeaders["anthropic-version"])
    }

    private fun createCredential(id: String, weight: Int, status: CredentialStatus = CredentialStatus.ACTIVE): AuthCredential {
        return AuthCredential(
            id = id,
            alias = "Test-$id",
            provider = ProviderType.CLAUDE,
            authType = AuthType.API_KEY,
            weight = weight,
            status = status
        )
    }
}
