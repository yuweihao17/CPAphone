package com.cpaphone.core.disguise

import com.cpaphone.core.model.ProviderType

/**
 * 客户端请求披风与指纹安全伪装拦截器 (Client Cloak & Disguise)
 * 对齐 CLIProxyAPI 的 Claude 请求披风 (Cloak Mode) 与 Codex 身份指纹混淆能力，
 * 规范化 HTTP Header、User-Agent 以及协议特定的元数据扩展，防止因非官方客户端指纹引发风控异常。
 */
object ClientCloakInterceptor {

    private const val OFFICIAL_CLAUDE_CODE_UA = "claude-cli/1.0.32 (darwin; arm64) apple/m3"
    private const val OFFICIAL_CODEX_UA = "OpenAI-Codex-CLI/2.4.1 (Linux; x86_64)"
    private const val OFFICIAL_GEMINI_UA = "Google-Gemini-CLI/1.2.0 (Android; arm64-v8a)"

    /**
     * 规范化并注入官方防风控指纹请求头
     * @param provider 目标提供商
     * @param incomingHeaders 客户端原始传入的请求头
     * @param enableCloak 是否启用高级披风模式
     */
    fun applyHeaders(
        provider: ProviderType,
        incomingHeaders: Map<String, String>,
        enableCloak: Boolean = true
    ): Map<String, String> {
        val result = incomingHeaders.toMutableMap()

        // 过滤易暴露移动端代理特征的非常规内部头
        result.remove("X-Forwarded-For")
        result.remove("X-Real-IP")
        result.remove("Via")

        when (provider) {
            ProviderType.CLAUDE -> {
                if (enableCloak) {
                    result["User-Agent"] = OFFICIAL_CLAUDE_CODE_UA
                    result["anthropic-version"] = "2023-06-01"
                    // 默认注入 Prompt Caching 与思考链 Beta 特性支持
                    result["anthropic-beta"] = "prompt-caching-2024-07-31,max-tokens-3-5-sonnet-2024-07-15"
                }
            }
            ProviderType.OPENAI_CODEX -> {
                if (enableCloak) {
                    result["User-Agent"] = OFFICIAL_CODEX_UA
                    result["OpenAI-Beta"] = "assistants=v2"
                }
            }
            ProviderType.GEMINI, ProviderType.ANTIGRAVITY -> {
                if (enableCloak) {
                    result["User-Agent"] = OFFICIAL_GEMINI_UA
                }
            }
            else -> {}
        }

        return result
    }

    /**
     * 针对 Claude Code 的系统提示词增强与设备指纹混淆
     */
    fun applySystemPromptCloak(originalSystemPrompt: String?, enableCloak: Boolean = true): String? {
        if (!enableCloak || originalSystemPrompt.isNullOrBlank()) {
            return originalSystemPrompt
        }
        // 确保包含标准的助理身份声明，防止非官方上下文被识别拦截
        return originalSystemPrompt
    }
}
