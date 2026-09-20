package com.cpaphone.core

import com.cpaphone.core.translator.GeminiSchemaCleaner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Gemini 系上游工具 schema 清洗器测试
 * 复现外部 MCP 客户端（dd）工具集导致的上游 400 "Unknown name x-mcp-header" 场景
 */
class GeminiSchemaCleanerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `删除 x- 扩展字段（dd MCP 工具 400 根因）`() {
        val schema = """
            {
              "type": "object",
              "x-mcp-header": {"version": "1.0"},
              "properties": {
                "query": {
                  "type": "string",
                  "x-mcp-description": "搜索词"
                }
              },
              "x-tool-meta": "anything"
            }
        """.trimIndent()

        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject

        assertNull(cleaned["x-mcp-header"], "顶层 x-* 应删除")
        assertNull(cleaned["x-tool-meta"], "顶层 x-* 应删除")
        val queryProp = cleaned["properties"]!!.jsonObject["query"]!!.jsonObject
        assertNull(queryProp["x-mcp-description"], "嵌套 x-* 应删除")
        assertEquals("string", queryProp["type"]!!.jsonPrimitive.content, "正常字段应保留")
    }

    @Test
    fun `properties 中恰好叫 x-foo 的属性名不误删`() {
        val schema = """
            {
              "type": "object",
              "properties": {
                "x-mcp-header": {"type": "string", "description": "这是一个名叫 x-mcp-header 的业务属性"}
              }
            }
        """.trimIndent()

        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject
        val prop = cleaned["properties"]!!.jsonObject["x-mcp-header"]?.jsonObject
            ?: fail("properties 内的同名属性不得被误删")
        assertEquals("string", prop["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `删除美元系与限制性关键字`() {
        val schema = """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "${'$'}id": "https://example.com/tool",
              "type": "object",
              "additionalProperties": false,
              "minLength": 1,
              "pattern": "^[a-z]+$",
              "properties": {
                "q": {"type": "string", "minLength": 2, "maxLength": 10, "default": "hi", "format": "date"}
              },
              "required": ["q", "ghost"]
            }
        """.trimIndent()

        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject

        assertNull(cleaned["\$schema"])
        assertNull(cleaned["\$id"])
        assertNull(cleaned["additionalProperties"])
        assertNull(cleaned["minLength"])
        assertNull(cleaned["pattern"])
        val q = cleaned["properties"]!!.jsonObject["q"]!!.jsonObject
        assertNull(q["minLength"])
        assertNull(q["maxLength"])
        assertNull(q["default"])
        // required 清理：ghost 指向不存在的属性 → 删除
        assertEquals(listOf("q"), cleaned["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `type 数组取非 null 类型并补 nullable`() {
        val schema = """{"type": ["string", "null"], "properties": {}}"""
        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject
        assertEquals("string", cleaned["type"]!!.jsonPrimitive.content)
        assertEquals(true, cleaned["nullable"]!!.jsonPrimitive.content == "true" || cleaned["nullable"] != null)
    }

    @Test
    fun `allOf 合并与 anyOf 取分支`() {
        val schema = """
            {
              "type": "object",
              "allOf": [
                {"properties": {"a": {"type": "string"}}, "required": ["a"]},
                {"properties": {"b": {"type": "number"}}, "required": ["b"]}
              ],
              "anyOf": [
                {"properties": {"c": {"type": "boolean"}}, "required": ["c"]},
                {"properties": {"d": {"type": "string"}}}
              ]
            }
        """.trimIndent()

        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject
        assertNull(cleaned["allOf"])
        assertNull(cleaned["anyOf"])
        val props = cleaned["properties"]!!.jsonObject
        assertEquals("string", props["a"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("number", props["b"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["c"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(props["d"], "anyOf 只取第一个分支")
        val required = cleaned["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `const 转为 description 提示`() {
        val schema = """{"const": "fixed_value", "description": "原始说明"}"""
        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject
        assertNull(cleaned["const"])
        val desc = cleaned["description"]!!.jsonPrimitive.content
        assertTrue(desc.startsWith("原始说明") && desc.contains("fixed_value"), "hint 应追加在原 description 后: $desc")
    }

    @Test
    fun `数字 enum 整体删除并写 hint（proto enum 强制 TYPE_STRING）`() {
        val schema = """{"type": "number", "enum": [1, 2, 3]}"""
        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject
        assertNull(cleaned["enum"])
        assertEquals("(Accepts: 1 | 2 | 3)", cleaned["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ref 与 defs 整体删除`() {
        val schema = """
            {
              "type": "object",
              "${'$'}defs": {"Name": {"type": "string"}},
              "properties": {
                "name": {"${'$'}ref": "#/${'$'}defs/Name"}
              }
            }
        """.trimIndent()

        val cleaned = GeminiSchemaCleaner.clean(json.parseToJsonElement(schema)).jsonObject
        assertNull(cleaned["\$defs"])
        val nameProp = cleaned["properties"]!!.jsonObject["name"]!!.jsonObject
        assertNull(nameProp["\$ref"], "proto 无法解析 \$ref，删除后至少不 400")
    }

    @Test
    fun `dd 完整 MCP 工具集形态端到端清洗`() {
        // 模拟 dd 客户端的 MCP 工具（39 个中的第 39 个，带 x-mcp-header）
        val toolJson = """
            {
              "type": "function",
              "function": {
                "name": "mcp__web__search",
                "description": "搜索网页",
                "parameters": {
                  "type": "object",
                  "x-mcp-header": {"server": "web"},
                  "properties": {
                    "query": {"type": ["string", "null"], "description": "关键词", "x-mcp-meta": 1},
                    "limit": {"type": "number", "const": 5},
                    "options": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {"safe": {"type": "boolean", "default": false}}
                    }
                  },
                  "required": ["query", "limit", "nonexistent"]
                }
              }
            }
        """.trimIndent()

        val function = json.parseToJsonElement(toolJson).jsonObject["function"]!!.jsonObject
        val cleaned = GeminiSchemaCleaner.clean(function["parameters"]!!).jsonObject

        assertNull(cleaned["x-mcp-header"])
        val query = cleaned["properties"]!!.jsonObject["query"]!!.jsonObject
        assertEquals("string", query["type"]!!.jsonPrimitive.content)
        assertNull(query["x-mcp-meta"])
        // const → description hint（proto enum 强制 TYPE_STRING，数字值不能进 enum）
        val limit = cleaned["properties"]!!.jsonObject["limit"]!!.jsonObject
        assertNull(limit["enum"])
        assertTrue(limit["description"]!!.jsonPrimitive.content.contains("Accepts: 5"))
        // required 清理 nonexistent
        val required = cleaned["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.containsAll(listOf("query", "limit")) && !required.contains("nonexistent"))
        // 嵌套对象 additionalProperties 删除、default 删除
        val options = cleaned["properties"]!!.jsonObject["options"]!!.jsonObject
        assertNull(options["additionalProperties"])
        val safe = options["properties"]!!.jsonObject["safe"]!!.jsonObject
        assertNull(safe["default"])
    }
}
