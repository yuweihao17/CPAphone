package com.cpaphone.core.model

/**
 * 静态模型目录（对齐 CLIProxyAPI internal/registry/models/models.json 的分发策略）
 * /v1/models 与模型路由按「凭据池中实际存在的 provider」动态聚合，不再返回与凭据无关的硬编码清单
 */
object ModelCatalog {

    /** 各提供商可供的模型清单（ID 原样对齐 CLIProxyAPI 上游目录） */
    val MODELS_BY_PROVIDER: Map<ProviderType, List<String>> = mapOf(
        ProviderType.CLAUDE to listOf(
            "claude-haiku-4-5-20251001",
            "claude-sonnet-4-5-20250929",
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-opus-4-5-20251101",
            "claude-opus-4-1-20250805",
            "claude-opus-4-20250514",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-haiku-20241022"
        ),
        ProviderType.ANTIGRAVITY to listOf(
            "gemini-3-flash",
            "gemini-3.5-flash-high",
            "gemini-3.5-flash-low",
            "gemini-3.5-flash-extra-low",
            "gemini-3.7-flash-high",
            "gemini-3.1-flash-lite",
            "gemini-3.1-flash-image",
            "gemini-3.1-pro-low",
            "gemini-pro-agent",
            "gemini-3-flash-agent",
            "gpt-oss-120b-medium",
            "claude-sonnet-4-6",
            "claude-opus-4-6-thinking"
        ),
        ProviderType.OPENAI_CODEX to listOf(
            "gpt-5.4-mini",
            "gpt-5.4",
            "gpt-5.5",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.6-sol",
            "codex-auto-review",
            "gpt-image-1.5",
            "gpt-image-2"
        ),
        ProviderType.GEMINI to listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3-pro-preview",
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.7-flash"
        ),
        ProviderType.VERTEX_AI to listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3-pro-preview",
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "gemini-3.5-flash",
            "gemini-3.7-flash"
        ),
        ProviderType.KIMI to listOf(
            "kimi-k2",
            "kimi-k2-thinking",
            "kimi-k2.5",
            "kimi-k2.6",
            "kimi-k2.7-code",
            "kimi-k2.7-code-highspeed",
            "kimi-k3"
        ),
        ProviderType.XAI to listOf(
            "grok-4.6",
            "grok-4.5",
            "grok-4.3",
            "grok-3-mini",
            "grok-3-mini-fast",
            "grok-build-0.1",
            "grok-composer-2.5-fast"
        ),
        ProviderType.OPENAI_COMPATIBLE to emptyList() // 自定义 Provider：模型由凭据 modelAliases 定义
    )

    /** modelId → 提供方集合（反向路由索引） */
    private val MODEL_OWNERS: Map<String, Set<ProviderType>> = buildMap {
        MODELS_BY_PROVIDER.forEach { (provider, models) ->
            models.forEach { model ->
                merge(model, setOf(provider)) { old, new -> old + new }
            }
        }
    }

    /**
     * 按凭据池聚合 /v1/models 清单
     * @return (模型 ID, owned_by) 列表；OPENAI_COMPATIBLE 凭据额外注册其 modelAliases 的别名键
     */
    fun aggregateForProviders(
        providers: Set<ProviderType>,
        compatAliases: Map<String, String> = emptyMap()
    ): List<Pair<String, String>> {
        val result = linkedMapOf<String, String>()
        providers.forEach { provider ->
            val ownedBy = when (provider) {
                ProviderType.CLAUDE -> "anthropic"
                ProviderType.GEMINI, ProviderType.VERTEX_AI, ProviderType.ANTIGRAVITY -> "google"
                ProviderType.OPENAI_CODEX, ProviderType.OPENAI_COMPATIBLE -> "openai"
                ProviderType.KIMI -> "moonshot"
                ProviderType.XAI -> "xai"
            }
            MODELS_BY_PROVIDER[provider]?.forEach { model ->
                result.putIfAbsent(model, ownedBy)
            }
        }
        compatAliases.forEach { alias, _ ->
            result.putIfAbsent(alias, "openai")
        }
        return result.map { it.key to it.value }
    }

    /** 反查模型由哪些提供商可供；未知模型（如自定义别名）返回 null 走特征匹配兜底 */
    fun ownersForModel(modelId: String): Set<ProviderType>? = MODEL_OWNERS[modelId]
}
