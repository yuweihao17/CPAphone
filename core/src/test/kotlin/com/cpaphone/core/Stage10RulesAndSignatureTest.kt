package com.cpaphone.core

import com.cpaphone.core.disguise.CloakObfuscator
import com.cpaphone.core.rules.*
import com.cpaphone.core.signature.SignatureDecisionAction
import com.cpaphone.core.signature.SignatureProvider
import com.cpaphone.core.signature.SignatureSniffer
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class Stage10RulesAndSignatureTest {

    @Test
    fun testPayloadRuleEngineExecutionOrder() {
        val initialPayload = """
            {
                "model": "gpt-4o",
                "stream": false,
                "unwanted_param": "debug_data"
            }
        """.trimIndent()

        // 构建 5 阶规则配置
        val config = PayloadConfig(
            // 1. Default: 仅当不存在时写入
            default = listOf(
                PayloadRule(
                    models = listOf(PayloadModelRule(name = "gpt-*")),
                    params = mapOf("temperature" to JsonPrimitive(0.7))
                )
            ),
            // 2. Override: 强制覆盖
            override = listOf(
                PayloadRule(
                    models = listOf(PayloadModelRule(name = "*")),
                    params = mapOf("stream" to JsonPrimitive(true))
                )
            ),
            // 3. Filter: 删除指定路径
            filter = listOf(
                PayloadFilterRule(
                    models = listOf(PayloadModelRule(name = "*")),
                    params = listOf("unwanted_param")
                )
            )
        )

        val modified = PayloadRuleEngine.apply(
            payloadJson = initialPayload,
            model = "gpt-4o",
            config = config
        )

        assertTrue(modified.contains("\"temperature\":0.7"))
        assertTrue(modified.contains("\"stream\":true"))
        assertFalse(modified.contains("unwanted_param"))
    }

    @Test
    fun testCloakObfuscatorLongestFirstAndInsertion() {
        val sensitiveWords = listOf("Claude", "ClaudeCode", "Antigravity", "Anthropic")
        val obfuscator = CloakObfuscator(sensitiveWords)

        val input = "Hello ClaudeCode and Claude, powered by Anthropic Antigravity."
        val obfuscated = obfuscator.obfuscateText(input)

        // 验证：长词 ClaudeCode 优先命中被混淆为 C\u200BlaudeCode，而不是 Claude 先被插值导致长词损坏
        assertTrue(obfuscated.contains("C\u200BlaudeCode"))
        assertTrue(obfuscated.contains("C\u200Blaude"))
        assertTrue(obfuscated.contains("A\u200Bnthropic"))
        assertTrue(obfuscated.contains("A\u200Bntigravity"))

        // 验证单词插入逻辑
        val singleWord = obfuscator.obfuscateWord("OpenAI")
        assertEquals("O\u200BpenAI", singleWord)
    }

    @Test
    fun testSignatureSnifferAndDecisionMatrix() {
        // 1. 测试特征嗅探
        assertEquals(SignatureProvider.CLAUDE, SignatureSniffer.detectSignatureProvider("C1234567890ABCDEF"))
        assertEquals(SignatureProvider.GPT, SignatureSniffer.detectSignatureProvider("gAAAAABnlwQ..."))
        assertEquals(SignatureProvider.GEMINI_BYPASS, SignatureSniffer.detectSignatureProvider("skip_thought_signature_validator"))

        // 2. 测试跨模型决策
        // 目标为 Gemini，遇到 Claude 签名，首个函数调用应替换为哨兵
        val geminiDecision = SignatureSniffer.decideAction(
            targetProvider = "gemini",
            detectedSignatureProvider = SignatureProvider.CLAUDE,
            isFirstFunctionCall = true
        )
        assertEquals(SignatureDecisionAction.REPLACE_WITH_GEMINI_BYPASS, geminiDecision)

        // 目标为 Claude，遇到外来 GPT 签名，必须彻底丢弃思考块避免 400
        val claudeDecision = SignatureSniffer.decideAction(
            targetProvider = "claude",
            detectedSignatureProvider = SignatureProvider.GPT,
            isFirstFunctionCall = false
        )
        assertEquals(SignatureDecisionAction.DROP_BLOCK, claudeDecision)

        // 目标为 Kimi，遇到 Claude 签名，仅剥离签名保留思考文本
        val kimiDecision = SignatureSniffer.decideAction(
            targetProvider = "kimi",
            detectedSignatureProvider = SignatureProvider.CLAUDE
        )
        assertEquals(SignatureDecisionAction.DROP_SIGNATURE, kimiDecision)
    }
}
