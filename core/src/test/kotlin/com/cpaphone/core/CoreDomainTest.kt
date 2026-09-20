package com.cpaphone.core

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.routing.FillFirstLoadBalancer
import com.cpaphone.core.routing.RoundRobinLoadBalancer
import com.cpaphone.core.routing.SmoothWeightedRoundRobinLoadBalancer
import com.cpaphone.core.session.CooldownManager
import com.cpaphone.core.session.SessionAffinityManager
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.core.translator.UnifiedContentPart
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

        // 6 次调度中，A 出现 5 次，B 出现 1 次，且平滑分散
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
    fun testCooldownManager() {
        val cooldown = CooldownManager(defaultCooldownMs = 200)
        assertFalse(cooldown.isCooling("c1"))

        cooldown.triggerCooldown("c1")
        assertTrue(cooldown.isCooling("c1"))
        assertTrue(cooldown.getRemainingCooldownMs("c1") > 0)

        cooldown.resetCooldown("c1")
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
    fun testProtocolTranslatorOpenAiToClaude() {
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

        val parsed = ProtocolTranslatorEngine.parseOpenAiChatRequest(openAiJson)
        assertEquals("gpt-4o", parsed.model)
        assertEquals("You are a helpful coding assistant.", parsed.systemPrompt)
        assertEquals(1, parsed.messages.size)
        assertEquals(UnifiedRole.USER, parsed.messages[0].role)
        assertEquals("Hello, write a quicksort.", parsed.messages[0].extractPlainText())
        assertTrue(parsed.isStreaming)

        val claudeJson = ProtocolTranslatorEngine.toClaudeMessagesJson(parsed, "claude-3-5-sonnet")
        assertTrue(claudeJson.contains("claude-3-5-sonnet"))
        assertTrue(claudeJson.contains("You are a helpful coding assistant."))
        assertTrue(claudeJson.contains("Hello, write a quicksort."))
    }

    private fun createCredential(id: String, weight: Int, status: CredentialStatus = CredentialStatus.ACTIVE): AuthCredential {
        return AuthCredential(
            id = id,
            alias = "Test-$id",
            provider = ProviderType.CLAUDE,
            weight = weight,
            status = status
        )
    }
}
