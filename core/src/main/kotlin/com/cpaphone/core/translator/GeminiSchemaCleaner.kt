package com.cpaphone.core.translator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gemini 系上游工具定义 JSON Schema 清洗器
 * （对齐 CLIProxyAPI internal/util/gemini_schema.go cleanJSONSchema 最小规则集）
 *
 * 背景：OpenAI/MCP 客户端传来的 tools[].function.parameters 含上游 proto 严格校验
 * 不认识的字段（x-mcp-header 等 vendor 扩展、$ref/$defs、限制性关键字），直接透传
 * 会被 daily-cloudcode-pa.googleapis.com 以 HTTP 400 "Unknown name ... Cannot find field" 拒绝。
 *
 * 规则（全树递归；properties 的键是「属性名」而非关键字，绝不误删）：
 * 1. 组合展开：allOf 浅合并进父级；anyOf/oneOf 取第一个分支；type 数组取首个非 null 类型（含 null 时补 nullable:true）；const 转单元素 enum
 * 2. 黑名单删除：x-* 扩展、$schema/$id/$anchor/$comment/$ref/$defs/definitions、
 *    additionalProperties/propertyNames/patternProperties、if/then/else/not、
 *    uniqueItems/contains/minLength/maxLength/pattern/minItems/maxItems/exclusiveMinimum/exclusiveMaximum、
 *    minimum/maximum/multipleOf、default/examples/title
 * 3. required 清理：删除指向不存在属性的项
 */
object GeminiSchemaCleaner {

    private val bannedKeys = setOf(
        // JSON Schema 元数据与引用（proto 无法解析 $ref，直接删除）
        "\$schema", "\$id", "\$anchor", "\$comment", "\$ref", "\$defs", "definitions",
        // 供应商扩展（MCP/OpenAPI x-* 由前缀规则单独处理）
        // 结构组合关键字（expand 阶段已消化，防御性兜底）
        "allOf", "anyOf", "oneOf",
        // proto 不认识的对象结构控制
        "additionalProperties", "propertyNames", "patternProperties",
        "if", "then", "else", "not",
        // 限制性关键字（CLIProxyAPI 将其挪入 description 后删除；此处直接删除）
        "uniqueItems", "contains", "minLength", "maxLength", "pattern",
        "minItems", "maxItems", "exclusiveMinimum", "exclusiveMaximum",
        "minimum", "maximum", "multipleOf",
        "default", "examples", "title", "deprecated",
        // enum/const：proto enum 元素强制 TYPE_STRING，数字/布尔枚举值会 400；
        // 对齐 CLIProxyAPI Antigravity Tool 行为——全删并写入 description hint
        "const"
    )

    fun clean(schema: JsonElement): JsonElement = cleanupRequired(stripUnsupported(expandCompositions(schema)))

    // =========================================================================
    // 阶段 1：组合展开（allOf 合并 / anyOf、oneOf 取分支 / type 数组 / const）
    // =========================================================================

    private fun expandCompositions(node: JsonElement): JsonElement {
        when (node) {
            is JsonObject -> {
                // 先递归展开子级
                val expanded = buildJsonObject { node.forEach { (k, v) -> put(k, expandCompositions(v)) } }

                var type: JsonElement? = expanded["type"]
                var nullable = false
                val props = LinkedHashMap<String, JsonElement>()
                (expanded["properties"] as? JsonObject)?.forEach { (k, v) -> props[k] = v }
                val required = LinkedHashSet<JsonElement>()
                (expanded["required"] as? JsonArray)?.forEach { required.add(it) }

                // allOf：合并各分支的 properties/required/type（父级优先、required 并集）
                (expanded["allOf"] as? JsonArray)?.forEach { branchElem ->
                    val branch = branchElem as? JsonObject ?: return@forEach
                    (branch["properties"] as? JsonObject)?.forEach { (k, v) -> props.putIfAbsent(k, v) }
                    (branch["required"] as? JsonArray)?.forEach { required.add(it) }
                    if (type == null && branch["type"] != null) type = branch["type"]
                }

                // anyOf/oneOf：取第一个分支（CLIProxyAPI 取最优分支，MCP 工具场景首分支足够）
                ((expanded["anyOf"] ?: expanded["oneOf"]) as? JsonArray)?.firstOrNull()?.let { branchElem ->
                    val branch = branchElem as? JsonObject ?: return@let
                    (branch["properties"] as? JsonObject)?.forEach { (k, v) -> props.putIfAbsent(k, v) }
                    (branch["required"] as? JsonArray)?.forEach { required.add(it) }
                    if (type == null && branch["type"] != null) type = branch["type"]
                }

                // type 数组：取首个非 null 类型；含 null 补 nullable:true（Antigravity 原生支持）
                (type as? JsonArray)?.let { typeArr ->
                    val hasNull = typeArr.any { (it as? JsonPrimitive)?.content == "null" }
                    typeArr.firstOrNull { (it as? JsonPrimitive)?.content != "null" }?.let { first ->
                        type = first
                        if (hasNull) nullable = true
                    }
                }

                // enum/const 的收敛在 stripUnsupported 阶段（→ description hint）
                return buildJsonObject {
                    expanded.forEach { (k, v) ->
                        if (k !in setOf("allOf", "anyOf", "oneOf", "type", "properties", "required")) {
                            put(k, v)
                        }
                    }
                    type?.let { put("type", it) }
                    if (nullable) put("nullable", JsonPrimitive(true))
                    if (props.isNotEmpty()) {
                        put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
                    }
                    if (required.isNotEmpty()) {
                        put("required", buildJsonArray { required.forEach { add(it) } })
                    }
                }
            }
            is JsonArray -> return buildJsonArray { node.forEach { add(expandCompositions(it)) } }
            else -> return node
        }
    }

    // =========================================================================
    // 阶段 2：黑名单键递归删除（properties 键 = 属性名，强制保留）
    // =========================================================================

    private fun stripUnsupported(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> buildJsonObject {
            // enum/const：proto enum 元素强制 TYPE_STRING，非字符串值必 400 →
            // 对齐 CLIProxyAPI：删除枚举并把可选值写进 description 提示模型
            val enumValues = ArrayList<String>()
            node["enum"]?.let { enumElem ->
                (enumElem as? JsonArray)?.forEach { v ->
                    enumValues.add(when (v) {
                        is JsonPrimitive -> if (v.isString) v.content else v.toString()
                        else -> v.toString()
                    })
                }
            }
            node["const"]?.let { c ->
                enumValues.add(
                    when (c) {
                        is JsonPrimitive -> if (c.isString) c.content else c.toString()
                        else -> c.toString()
                    }
                )
            }

            node.forEach { (key, value) ->
                when {
                    key == "properties" && value is JsonObject -> {
                        // 属性名映射：键是用户定义的属性名（可能恰好叫 x-foo），不删；值继续清洗
                        put("properties", buildJsonObject {
                            value.forEach { (propName, propSchema) -> put(propName, stripUnsupported(propSchema)) }
                        })
                    }
                    key == "enum" || key == "const" -> { /* 已收敛为 description hint */ }
                    isBannedKey(key) -> { /* 删除 */ }
                    else -> put(key, stripUnsupported(value))
                }
            }
            if (enumValues.isNotEmpty()) {
                val hint = "Accepts: ${enumValues.joinToString(" | ")}"
                val existing = (node["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                put("description", JsonPrimitive(existing?.let { "$it\n($hint)" } ?: "($hint)"))
            }
        }
        is JsonArray -> buildJsonArray { node.forEach { add(stripUnsupported(it)) } }
        else -> node
    }

    private fun isBannedKey(key: String): Boolean =
        key in bannedKeys || key.startsWith("x-")

    // =========================================================================
    // 阶段 3：required 清理（删除指向不存在属性的项）
    // =========================================================================

    private fun cleanupRequired(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> buildJsonObject {
            node.forEach { (key, value) ->
                if (key == "required" && value is JsonArray) {
                    val props = node["properties"] as? JsonObject
                    put("required", buildJsonArray {
                        value.forEach { item ->
                            val name = (item as? JsonPrimitive)?.content ?: return@forEach
                            if (props == null || props.containsKey(name)) add(item)
                        }
                    })
                } else {
                    put(key, cleanupRequired(value))
                }
            }
        }
        is JsonArray -> buildJsonArray { node.forEach { add(cleanupRequired(it)) } }
        else -> node
    }
}
