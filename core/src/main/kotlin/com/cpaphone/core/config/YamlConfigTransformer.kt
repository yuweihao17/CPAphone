package com.cpaphone.core.config

import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.RoutingStrategyType

/**
 * 原生 YAML 配置文件双向转换器
 * 负责在内存配置/凭据池与标准 CLIProxyAPI config.yaml 之间进行无损格式化与解析
 */
object YamlConfigTransformer {

    /**
     * 将配置与凭据池导出为标准 CLIProxyAPI YAML 格式文本
     */
    fun exportToYaml(
        port: Int,
        allowLan: Boolean,
        strategy: RoutingStrategyType,
        safeMode: Boolean,
        cloaking: Boolean,
        proxyUrl: String?,
        credentials: List<AuthCredential>
    ): String {
        val sb = StringBuilder()
        sb.append("# =============================================================================\n")
        sb.append("# CPAphone (CLIProxyAPI for Android) Exported Configuration\n")
        sb.append("# =============================================================================\n\n")

        sb.append("host: \"${if (allowLan) "0.0.0.0" else "127.0.0.1"}\"\n")
        sb.append("port: $port\n")
        sb.append("debug: false\n")
        sb.append("commercial-mode: true\n\n")

        if (!proxyUrl.isNullOrBlank()) {
            sb.append("proxy-url: \"$proxyUrl\"\n\n")
        }

        sb.append("routing:\n")
        sb.append("  strategy: \"${when (strategy) {
            RoutingStrategyType.ROUND_ROBIN -> "round-robin"
            RoutingStrategyType.WEIGHTED_ROUND_ROBIN -> "weighted-round-robin"
            RoutingStrategyType.FILL_FIRST -> "fill-first"
        }}\"\n")
        sb.append("  session-affinity: true\n")
        sb.append("  session-affinity-ttl: \"1h\"\n\n")

        sb.append("safe-mode: $safeMode\n")
        sb.append("enable-cloaking: $cloaking\n\n")

        sb.append("# 凭据池定义\n")
        sb.append("credentials:\n")
        if (credentials.isEmpty()) {
            sb.append("  []\n")
        } else {
            credentials.forEach { cred ->
                sb.append("  - id: \"${cred.id}\"\n")
                sb.append("    alias: \"${cred.alias}\"\n")
                sb.append("    provider: \"${cred.provider.identifier}\"\n")
                sb.append("    auth-type: \"${cred.authType.name}\"\n")
                sb.append("    weight: ${cred.weight}\n")
                sb.append("    status: \"${cred.status.name}\"\n")
                if (!cred.customBaseUrl.isNullOrBlank()) {
                    sb.append("    base-url: \"${cred.customBaseUrl}\"\n")
                }
            }
        }

        return sb.toString()
    }

    /**
     * 解析输入的 YAML 文本并提取基本配置项（轻量级无第三方依赖实现）
     */
    fun parseYamlBasic(yamlText: String): ParsedYamlResult {
        var port = 8317
        var allowLan = false
        var strategy = RoutingStrategyType.WEIGHTED_ROUND_ROBIN
        var proxyUrl: String? = null
        var safeMode = true

        yamlText.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isEmpty()) return@forEach

            val parts = trimmed.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim().trim('"', '\'')

                when (key) {
                    "port" -> port = value.toIntOrNull() ?: port
                    "host" -> allowLan = (value == "0.0.0.0" || value == "")
                    "proxy-url" -> proxyUrl = if (value.isNotBlank()) value else null
                    "strategy" -> strategy = when (value) {
                        "round-robin" -> RoutingStrategyType.ROUND_ROBIN
                        "fill-first" -> RoutingStrategyType.FILL_FIRST
                        else -> RoutingStrategyType.WEIGHTED_ROUND_ROBIN
                    }
                    "safe-mode" -> safeMode = value.toBooleanStrictOrNull() ?: safeMode
                }
            }
        }

        return ParsedYamlResult(
            port = port,
            allowLan = allowLan,
            strategy = strategy,
            proxyUrl = proxyUrl,
            safeMode = safeMode
        )
    }

    data class ParsedYamlResult(
        val port: Int,
        val allowLan: Boolean,
        val strategy: RoutingStrategyType,
        val proxyUrl: String?,
        val safeMode: Boolean
    )
}
