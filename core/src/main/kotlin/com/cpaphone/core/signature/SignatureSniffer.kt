package com.cpaphone.core.signature

/**
 * 思考签名提供商枚举
 */
enum class SignatureProvider(val identifier: String) {
    UNKNOWN("unknown"),
    CLAUDE("claude"),
    GEMINI("gemini"),
    GEMINI_BYPASS("gemini_bypass"),
    GPT("gpt"),
    KIMI("kimi"),
    GROK("grok")
}

/**
 * 跨模型签名决策动作枚举
 */
enum class SignatureDecisionAction {
    PRESERVE,                     // 保持原样 (目标厂商一致)
    REPLACE_WITH_GEMINI_BYPASS,   // 替换为 Gemini 哨兵 Bypass 绕过上游校验
    DROP_SIGNATURE,               // 仅剥离签名字段，保留思考内容 (如发给 Kimi)
    DROP_BLOCK                    // 彻底移除整段思考块 (避免目标厂商 400 报错)
}

/**
 * 深度思考签名嗅探状态机与决策器
 * 对齐 CLIProxyAPI internal/signature/ 架构
 */
object SignatureSniffer {

    const val GEMINI_SKIP_THOUGHT_VALIDATOR = "skip_thought_signature_validator"

    /**
     * O(1) 首字符特征探测快速识别签名所属厂商
     */
    fun detectSignatureProvider(signature: String?): SignatureProvider {
        if (signature.isNullOrBlank()) return SignatureProvider.UNKNOWN
        val trimmed = signature.trim()

        // 显式哨兵识别
        if (trimmed == GEMINI_SKIP_THOUGHT_VALIDATOR || trimmed.contains("skip_thought_signature")) {
            return SignatureProvider.GEMINI_BYPASS
        }

        val firstChar = trimmed[0]
        return when (firstChar) {
            'C' -> SignatureProvider.CLAUDE  // Claude CAIS Envelope (0x08)
            'E' -> {
                // Claude 单层 Base64 (0x12) 或 Gemini Protobuf
                if (trimmed.length > 50 && trimmed.contains("==")) SignatureProvider.CLAUDE else SignatureProvider.GEMINI
            }
            'R' -> SignatureProvider.CLAUDE  // Claude 双层 Base64 (以 'E' 0x45 开头)
            'g' -> {
                // GPT / Codex Fernet Reasoning 密文 (gAAAA...)
                if (trimmed.startsWith("gAAAA")) SignatureProvider.GPT else SignatureProvider.UNKNOWN
            }
            else -> {
                // Kimi 常数长度与特征
                if (trimmed.length == 12946 || trimmed.length == 4340) {
                    SignatureProvider.KIMI
                } else {
                    SignatureProvider.UNKNOWN
                }
            }
        }
    }

    /**
     * 跨模型签名兼容性决策矩阵
     * @param targetProvider 目标提供商
     * @param detectedSignatureProvider 从原始签名嗅探出的厂商
     * @param isFirstFunctionCall 是否为模型回合中的第一个函数调用
     */
    fun decideAction(
        targetProvider: String,
        detectedSignatureProvider: SignatureProvider,
        isFirstFunctionCall: Boolean = false
    ): SignatureDecisionAction {
        val normTarget = targetProvider.lowercase()

        // 1. 目标厂商一致：原样保留
        if (normTarget.contains("claude") && detectedSignatureProvider == SignatureProvider.CLAUDE) {
            return SignatureDecisionAction.PRESERVE
        }
        if (normTarget.contains("gemini") && detectedSignatureProvider == SignatureProvider.GEMINI) {
            return SignatureDecisionAction.PRESERVE
        }
        if ((normTarget.contains("codex") || normTarget.contains("openai")) && detectedSignatureProvider == SignatureProvider.GPT) {
            return SignatureDecisionAction.PRESERVE
        }
        if (normTarget.contains("kimi") && detectedSignatureProvider == SignatureProvider.KIMI) {
            return SignatureDecisionAction.PRESERVE
        }

        // 2. 目标为 Gemini：首个函数调用注入哨兵 Bypass，其余丢弃
        if (normTarget.contains("gemini") || normTarget.contains("antigravity")) {
            return if (isFirstFunctionCall) {
                SignatureDecisionAction.REPLACE_WITH_GEMINI_BYPASS
            } else {
                SignatureDecisionAction.DROP_BLOCK
            }
        }

        // 3. 目标为 Kimi：Kimi 不需要签名，剥离签名即可保留思考文本
        if (normTarget.contains("kimi")) {
            return SignatureDecisionAction.DROP_SIGNATURE
        }

        // 4. 其它跨厂商互调（如发给 Claude、GPT）：必须彻底移除外来思考块，避免上游 400 Bad Request
        return SignatureDecisionAction.DROP_BLOCK
    }
}
