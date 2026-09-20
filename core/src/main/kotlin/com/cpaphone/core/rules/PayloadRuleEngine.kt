package com.cpaphone.core.rules

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * 模型匹配条件规则定义
 */
@Serializable
data class PayloadModelRule(
    val name: String = "*",                     // 模型名通配符 (如 "gpt-*", "*-5", "claude-*", "*")
    val protocol: String? = null,               // 目标协议约束 (如 "gemini", "claude")
    val fromProtocol: String? = null,           // 来源协议约束 (如 "openai")
    val headers: Map<String, String> = emptyMap(), // 请求头模式匹配
    val match: Map<String, JsonElement> = emptyMap(),    // 字段值匹配断言
    val notMatch: Map<String, JsonElement> = emptyMap(), // 字段值不匹配断言
    val exist: List<String> = emptyList(),      // 字段存在性断言
    val notExist: List<String> = emptyList()    // 字段不存在断言
)

/**
 * 字段写入规则
 */
@Serializable
data class PayloadRule(
    val models: List<PayloadModelRule> = emptyList(),
    val params: Map<String, JsonElement> = emptyMap() // 写入字段字典 (支持多级路径，如 "temperature", "stream")
)

/**
 * 字段过滤删除规则
 */
@Serializable
data class PayloadFilterRule(
    val models: List<PayloadModelRule> = emptyList(),
    val params: List<String> = emptyList() // 待删除路径列表
)

/**
 * 动态 Payload 全量配置结构
 */
@Serializable
data class PayloadConfig(
    val default: List<PayloadRule> = emptyList(),
    val defaultRaw: List<PayloadRule> = emptyList(),
    val override: List<PayloadRule> = emptyList(),
    val overrideRaw: List<PayloadRule> = emptyList(),
    val filter: List<PayloadFilterRule> = emptyList()
)

/**
 * 动态 Payload 参数修改规则引擎
 * 严格按照 CLIProxyAPI 5 阶顺序执行：
 * 1. Default (缺失时写入，First-write-wins)
 * 2. DefaultRaw (原始 JSON 片段写入，First-write-wins)
 * 3. Override (强制覆盖，Last-write-wins)
 * 4. OverrideRaw (原始 JSON 片段覆盖，Last-write-wins)
 * 5. Filter (路径逆序过滤删除)
 */
object PayloadRuleEngine {

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun apply(
        payloadJson: String,
        model: String,
        targetProtocol: String = "openai",
        fromProtocol: String = "openai",
        requestHeaders: Map<String, String> = emptyMap(),
        config: PayloadConfig
    ): String {
        val root = try {
            jsonParser.parseToJsonElement(payloadJson).jsonObject
        } catch (_: Exception) {
            return payloadJson
        }

        val workingMap = root.toMutableMap()
        val appliedDefaults = mutableSetOf<String>()

        // 1. Default (缺失写入，首次写入优先)
        for (rule in config.default) {
            if (isRuleMatched(rule.models, model, targetProtocol, fromProtocol, requestHeaders, workingMap)) {
                rule.params.forEach { (key, value) ->
                    if (!workingMap.containsKey(key) && !appliedDefaults.contains(key)) {
                        workingMap[key] = value
                        appliedDefaults.add(key)
                    }
                }
            }
        }

        // 2. DefaultRaw (原始 JSON 片段缺失写入)
        for (rule in config.defaultRaw) {
            if (isRuleMatched(rule.models, model, targetProtocol, fromProtocol, requestHeaders, workingMap)) {
                rule.params.forEach { (key, rawJsonElem) ->
                    if (!workingMap.containsKey(key) && !appliedDefaults.contains(key)) {
                        workingMap[key] = rawJsonElem
                        appliedDefaults.add(key)
                    }
                }
            }
        }

        // 3. Override (强制覆盖，末次写入优先)
        for (rule in config.override) {
            if (isRuleMatched(rule.models, model, targetProtocol, fromProtocol, requestHeaders, workingMap)) {
                rule.params.forEach { (key, value) ->
                    workingMap[key] = value
                }
            }
        }

        // 4. OverrideRaw (原始 JSON 片段强制覆盖)
        for (rule in config.overrideRaw) {
            if (isRuleMatched(rule.models, model, targetProtocol, fromProtocol, requestHeaders, workingMap)) {
                rule.params.forEach { (key, rawJsonElem) ->
                    workingMap[key] = rawJsonElem
                }
            }
        }

        // 5. Filter (字段删除)
        for (rule in config.filter) {
            if (isRuleMatched(rule.models, model, targetProtocol, fromProtocol, requestHeaders, workingMap)) {
                rule.params.forEach { key ->
                    workingMap.remove(key)
                }
            }
        }

        return JsonObject(workingMap).toString()
    }

    private fun isRuleMatched(
        modelRules: List<PayloadModelRule>,
        model: String,
        targetProtocol: String,
        fromProtocol: String,
        requestHeaders: Map<String, String>,
        payloadMap: Map<String, JsonElement>
    ): Boolean {
        if (modelRules.isEmpty()) return true
        return modelRules.any { rule ->
            matchSingleRule(rule, model, targetProtocol, fromProtocol, requestHeaders, payloadMap)
        }
    }

    private fun matchSingleRule(
        rule: PayloadModelRule,
        model: String,
        targetProtocol: String,
        fromProtocol: String,
        requestHeaders: Map<String, String>,
        payloadMap: Map<String, JsonElement>
    ): Boolean {
        // 1. 模型名 Glob 通配匹配
        if (!globMatch(rule.name, model)) return false

        // 2. 目标协议匹配
        if (!rule.protocol.isNullOrBlank()) {
            if (!rule.protocol.equals(targetProtocol, ignoreCase = true)) return false
        }

        // 3. 来源协议匹配
        if (!rule.fromProtocol.isNullOrBlank()) {
            val normFrom = normalizeProtocol(fromProtocol)
            val normRule = normalizeProtocol(rule.fromProtocol)
            if (normFrom != normRule) return false
        }

        // 4. Header 模式匹配
        for ((hKey, hPattern) in rule.headers) {
            val actualValue = requestHeaders.entries.firstOrNull { it.key.equals(hKey, ignoreCase = true) }?.value
                ?: return false
            if (!globMatch(hPattern, actualValue)) return false
        }

        // 5. Exist 断言
        for (path in rule.exist) {
            if (!payloadMap.containsKey(path) || payloadMap[path] is JsonNull) return false
        }

        // 6. NotExist 断言
        for (path in rule.notExist) {
            if (payloadMap.containsKey(path) && payloadMap[path] !is JsonNull) return false
        }

        // 7. Match 断言
        for ((path, expected) in rule.match) {
            val actual = payloadMap[path] ?: return false
            if (actual != expected) return false
        }

        // 8. NotMatch 断言
        for ((path, unexpected) in rule.notMatch) {
            val actual = payloadMap[path]
            if (actual != null && actual == unexpected) return false
        }

        return true
    }

    /**
     * 基础 Glob 通配符匹配 (支持 '*')
     */
    fun globMatch(pattern: String, input: String): Boolean {
        if (pattern == "*" || pattern == input) return true
        val regexPattern = "^" + pattern.replace(".", "\\.").replace("*", ".*") + "$"
        return try {
            Regex(regexPattern, RegexOption.IGNORE_CASE).matches(input)
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeProtocol(proto: String): String {
        val lower = proto.lowercase()
        return when (lower) {
            "openai-response", "openai-responses", "response" -> "responses"
            else -> lower
        }
    }
}
