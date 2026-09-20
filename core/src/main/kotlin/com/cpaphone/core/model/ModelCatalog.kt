package com.cpaphone.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 静态模型目录（对齐 CLIProxyAPI internal/registry/models/models.json）
 * 支持远程热更新（对齐 model_updater.go）：拉取最新目录成功后覆盖静态清单，失败回退静态
 * /v1/models 与模型路由按「凭据池中实际存在的 provider」动态聚合
 */
object ModelCatalog {

    /** 各提供商可供的模型清单（内嵌基线，可被远程目录热更新覆盖） */
    @Volatile
    private var catalog: Map<ProviderType, List<String>> = baselineCatalog()

    private fun baselineCatalog(): Map<ProviderType, List<String>> = mapOf(
        ProviderType.CLAUDE to listOf(
            "claude-haiku-4-5-20251001",
            "claude-sonnet-4-5-20250929",
            "claude-sonnet-4-6",
            "claude-opus-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-opus-5",
            "claude-sonnet-5",
            "claude-fable-5",
            "claude-opus-4-5-20251101",
            "claude-opus-4-1-20250805",
            "claude-opus-4-20250514",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
            "claude-3-5-haiku-20241022"
        ),
        // OAuth Codex 默认 pro 档目录 + 内置注入的图像模型
        ProviderType.OPENAI_CODEX to listOf(
            "gpt-5.3-codex-spark",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.5",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "codex-auto-review",
            "gpt-image-1.5",
            "gpt-image-2"
        ),
        ProviderType.ANTIGRAVITY to listOf(
            "claude-opus-4-6-thinking",
            "claude-sonnet-4-6",
            "gemini-3.6-flash-high",
            "gemini-3.7-flash-high",
            "gemini-3.8-flash-high",
            "gemini-3-flash",
            "gemini-3-flash-agent",
            "gemini-3.1-flash-image",
            "gemini-3.1-flash-lite",
            "gemini-3.1-pro-low",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash-low",
            "gemini-3.5-flash-extra-low",
            "gemini-pro-agent",
            "gpt-oss-120b-medium"
        ),
        ProviderType.GEMINI to listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3-pro-preview",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-image-preview",
            "gemini-3-flash-preview",
            "gemini-3.1-flash-lite-preview",
            "gemini-3-pro-image-preview",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.7-flash"
        ),
        ProviderType.VERTEX_AI to listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-image",
            "gemini-2.5-flash-lite",
            "gemini-3-pro",
            "gemini-3-flash",
            "gemini-3.1-pro",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-image",
            "gemini-3.1-flash-lite",
            "gemini-3-pro-image",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-3.7-flash"
        ),
        ProviderType.KIMI to listOf(
            "kimi-k2",
            "kimi-k2-thinking",
            "kimi-k2.5",
            "kimi-k2.6",
            "kimi-k2.7-code",
            "kimi-k2.7-code-highspeed",
            "kimi-k3",
            "kimi-k3-256k"
        ),
        ProviderType.XAI to listOf(
            "grok-4.6",
            "grok-build-0.1",
            "grok-4.5",
            "grok-4.3",
            "grok-4.20-0309-reasoning",
            "grok-4.20-0309-non-reasoning",
            "grok-4.20-multi-agent-0309",
            "grok-3-mini",
            "grok-3-mini-fast",
            "grok-composer-2.5-fast"
        ),
        ProviderType.OPENAI_COMPATIBLE to emptyList() // 自定义 Provider：模型由凭据 modelAliases 定义
    )

    /** modelId → 提供方集合（反向路由索引，随热更新重建） */
    @Volatile
    private var modelOwners: Map<String, Set<ProviderType>> = buildOwners(catalog)

    private fun buildOwners(source: Map<ProviderType, List<String>>): Map<String, Set<ProviderType>> = buildMap {
        source.forEach { (provider, models) ->
            models.forEach { model ->
                merge(model, setOf(provider)) { old, new -> old + new }
            }
        }
    }

    /**
     * 应用远程热更新目录（结构对齐 CLIProxyAPI models.json：provider → [{id...}]）
     * codex 各订阅档（codex-free/team/plus/pro）取并集；gemini-cli 上游未启用故跳过
     * 解析失败或为空时静默保留当前目录（对齐上游"失败用内嵌副本兜底"策略）
     */
    fun applyRemoteCatalog(jsonText: String): Boolean {
        return try {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val parsed = mutableMapOf<ProviderType, MutableSet<String>>()
            root.forEach { (key, value) ->
                val provider = when {
                    key.equals("claude", true) -> ProviderType.CLAUDE
                    key.startsWith("codex", ignoreCase = true) -> ProviderType.OPENAI_CODEX
                    key.equals("gemini", true) -> ProviderType.GEMINI
                    key.equals("vertex", true) -> ProviderType.VERTEX_AI
                    key.equals("aistudio", true) -> ProviderType.GEMINI
                    key.equals("antigravity", true) -> ProviderType.ANTIGRAVITY
                    key.equals("kimi", true) -> ProviderType.KIMI
                    key.equals("xai", true) -> ProviderType.XAI
                    else -> return@forEach // gemini-cli 等未启用通道跳过
                }
                value.jsonArray.forEach { entry ->
                    val id = try {
                        entry.jsonObject["id"]?.jsonPrimitive?.content
                    } catch (_: Exception) {
                        null
                    }
                    if (!id.isNullOrBlank()) {
                        parsed.getOrPut(provider) { mutableSetOf() }.add(id)
                    }
                }
            }
            if (parsed.isEmpty()) {
                false
            } else {
                catalog = parsed.mapValues { it.value.toList() }
                modelOwners = buildOwners(catalog)
                true
            }
        } catch (_: Exception) {
            false
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
            catalog[provider]?.forEach { model ->
                result.putIfAbsent(model, ownedBy)
            }
        }
        compatAliases.forEach { alias, _ ->
            result.putIfAbsent(alias, "openai")
        }
        return result.map { it.key to it.value }
    }

    /** 反查模型由哪些提供商可供；未知模型（如自定义别名）返回 null 走特征匹配兜底 */
    fun ownersForModel(modelId: String): Set<ProviderType>? = modelOwners[modelId]
}
