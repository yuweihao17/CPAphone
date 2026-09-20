package com.cpaphone.engine

import com.cpaphone.core.model.PluginCapability
import com.cpaphone.core.model.PluginManifest
import com.cpaphone.core.model.PluginStatus
import com.cpaphone.engine.plugin.GuardedPluginClient
import com.cpaphone.engine.plugin.NativePluginHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PluginHostTest {

    @Test
    fun testPluginManifestValidation() {
        val validManifest = PluginManifest(
            id = "custom-provider-v1",
            name = "Custom Provider Plugin",
            version = "1.0.0",
            capabilities = listOf(PluginCapability.MODEL_PROVIDER, PluginCapability.EXECUTOR)
        )
        assertTrue(validManifest.isValid())

        val invalidManifest = PluginManifest(
            id = "invalid id with spaces!!",
            name = "Bad",
            version = "1.0"
        )
        assertFalse(invalidManifest.isValid())
    }

    @Test
    fun testGuardedPluginClientCircuitBreaker() {
        val manifest = PluginManifest(
            id = "test-plugin",
            name = "Test Plugin",
            version = "1.0.0"
        )

        // 模拟正常调用
        val normalClient = GuardedPluginClient(
            manifest = manifest,
            delegateCall = { method, requestJson ->
                "{\"ok\":true,\"result\":{\"echo\":\"$method\"}}"
            }
        )

        runBlocking {
            val response = normalClient.call("test.echo", "{}")
            assertTrue(response.ok)
            assertNotNull(response.result)
            assertEquals(PluginStatus.ACTIVE, normalClient.status)
        }

        // 模拟发生严重异常时触发熔断防崩护盾 (FUSED_CRASHED)
        val crashingClient = GuardedPluginClient(
            manifest = manifest,
            delegateCall = { _, _ ->
                throw RuntimeException("Fatal native SIGSEGV in .so")
            }
        )

        runBlocking {
            val crashResponse = crashingClient.call("test.crash", "{}")
            assertFalse(crashResponse.ok)
            assertEquals("plugin_crashed", crashResponse.error?.code)
            // 验证状态已变为熔断保护态
            assertEquals(PluginStatus.FUSED_CRASHED, crashingClient.status)

            // 后续调用自动被拦截，不再透传至崩溃引擎
            val blockedResponse = crashingClient.call("test.again", "{}")
            assertFalse(blockedResponse.ok)
            assertEquals("plugin_inactive", blockedResponse.error?.code)
        }
    }

    @Test
    @org.junit.Ignore("依赖 Android Context 运行时，桌面 JVM 无法构造；NativePluginHost 由真机 E2E 覆盖")
    fun testSha256Verification() {
        val host = NativePluginHost(createMockContext())
        val sampleData = "CPAphone Plugin Binary Content".toByteArray(Charsets.UTF_8)

        // 对 sampleData 计算标准 sha256: e05f5647565b9eafeacff6243881ca7cb4713cfa2863cb6ce8f0f0893aa822d5
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(sampleData).joinToString("") { "%02x".format(it) }

        assertTrue(host.verifySha256(sampleData, expectedSha))
        assertFalse(host.verifySha256(sampleData, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    private fun createMockContext(): android.content.Context {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cpaphone_test_cache").apply { mkdirs() }
        return java.lang.reflect.Proxy.newProxyInstance(
            android.content.Context::class.java.classLoader,
            arrayOf(android.content.Context::class.java)
        ) { _, method, _ ->
            if (method.name == "getCodeCacheDir") tempDir else null
        } as android.content.Context
    }
}
