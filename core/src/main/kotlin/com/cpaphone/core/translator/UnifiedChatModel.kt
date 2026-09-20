package com.cpaphone.core.translator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 统一抽象消息角色
 */
@Serializable
enum class UnifiedRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

/**
 * 结构化多模态与工具调用内容块
 */
@Serializable
sealed interface UnifiedContentPart {
    @Serializable
    data class Text(val text: String) : UnifiedContentPart

    @Serializable
    data class Thinking(
        val reasoning: String,
        val signature: String? = null
    ) : UnifiedContentPart

    @Serializable
    data class Image(
        val mimeType: String,
        val base64Data: String
    ) : UnifiedContentPart

    @Serializable
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    ) : UnifiedContentPart

    @Serializable
    data class ToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean = false
    ) : UnifiedContentPart
}

/**
 * 统一抽象消息
 */
@Serializable
data class UnifiedMessage(
    val role: UnifiedRole,
    val parts: List<UnifiedContentPart>
) {
    /** 辅助方法：快速获取主要文本内容 */
    fun extractPlainText(): String {
        return parts.filterIsInstance<UnifiedContentPart.Text>()
            .joinToString("\n") { it.text }
    }

    /** 辅助方法：快速获取思考链内容 */
    fun extractThinking(): String? {
        return parts.filterIsInstance<UnifiedContentPart.Thinking>()
            .firstOrNull()?.reasoning
    }
}

/**
 * 统一工具定义
 */
@Serializable
data class UnifiedToolDefinition(
    val name: String,
    val description: String,
    val parametersSchemaJson: JsonElement
)

/**
 * 统一内部请求模型 (Unified Chat Request)
 * 作为 OpenAI, Claude, Gemini 三大协议之间的中枢 AST 节点
 */
@Serializable
data class UnifiedChatRequest(
    val model: String,
    val messages: List<UnifiedMessage>,
    val systemPrompt: String? = null,
    val tools: List<UnifiedToolDefinition> = emptyMap<String, String>().let { emptyList() },
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val isStreaming: Boolean = false,
    val thinkingBudgetTokens: Int? = null
)
