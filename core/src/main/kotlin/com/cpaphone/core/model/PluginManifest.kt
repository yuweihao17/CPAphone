package com.cpaphone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 插件声明的能力开关枚举
 */
@Serializable
enum class PluginCapability {
    MODEL_PROVIDER,           // 提供专有大模型注册
    EXECUTOR,                 // 自定义请求执行器
    REQUEST_INTERCEPTOR,      // 请求前置拦截修改
    RESPONSE_INTERCEPTOR,     // 响应后置拦截修改
    RESPONSE_STREAM_INTERCEPTOR, // SSE 流式 Chunk 拦截器
    THINKING_APPLIER          // 思考链深度定制处理
}

/**
 * 插件生命周期状态机
 */
@Serializable
enum class PluginStatus {
    NOT_INSTALLED,   // 未安装
    INSTALLED,       // 已安装但未加载
    ACTIVE,          // 运行中活跃
    DISABLED,        // 手动停用
    FUSED_CRASHED    // 发生异常，熔断防崩中
}

/**
 * 插件目标系统架构
 */
@Serializable
data class PluginArtifact(
    val os: String = "android",
    val arch: String = "arm64-v8a",
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0L
)

/**
 * 插件清单 (Manifest)，对齐 CLIProxyAPI 规范
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val repository: String = "",
    val capabilities: List<PluginCapability> = emptyList(),
    val artifacts: List<PluginArtifact> = emptyList(),
    val entrySymbol: String = "cliproxy_plugin_init"
) {
    /**
     * 校验插件清单的合规性与安全性
     */
    fun isValid(): Boolean {
        val idRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        if (!idRegex.matches(id)) return false
        if (name.isBlank() || version.isBlank()) return false
        return true
    }
}

/**
 * 统一 C-ABI RPC 错误结构体
 */
@Serializable
data class PluginRpcError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val httpStatus: Int = 500
)

/**
 * 统一 C-ABI JSON 通信信封协议 (Envelope)
 */
@Serializable
data class PluginRpcEnvelope(
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: PluginRpcError? = null
)
