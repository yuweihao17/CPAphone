package com.cpaphone.engine.plugin

import android.content.Context
import com.cpaphone.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 动态插件客户端代理抽象（支持仿真模拟与 NDK 原生动态库）
 */
interface IPluginClient {
    val manifest: PluginManifest
    var status: PluginStatus
    suspend fun call(method: String, requestJson: String): PluginRpcEnvelope
    fun shutdown()
}

/**
 * 带有并发计数与崩溃熔断护盾的插件客户端封装
 */
class GuardedPluginClient(
    override val manifest: PluginManifest,
    private val delegateCall: suspend (method: String, requestJson: String) -> String,
    private val onShutdown: () -> Unit = {}
) : IPluginClient {
    override var status: PluginStatus = PluginStatus.ACTIVE
    private val callCounter = AtomicInteger(0)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun call(method: String, requestJson: String): PluginRpcEnvelope {
        if (status != PluginStatus.ACTIVE) {
            return PluginRpcEnvelope(
                ok = false,
                error = PluginRpcError(
                    code = "plugin_inactive",
                    message = "Plugin ${manifest.id} is currently $status",
                    httpStatus = 503
                )
            )
        }

        callCounter.incrementAndGet()
        try {
            val rawResponse = delegateCall(method, requestJson)
            return json.decodeFromString<PluginRpcEnvelope>(rawResponse)
        } catch (e: Exception) {
            // 捕获异常触发熔断防崩护盾 (Circuit Breaker)
            status = PluginStatus.FUSED_CRASHED
            return PluginRpcEnvelope(
                ok = false,
                error = PluginRpcError(
                    code = "plugin_crashed",
                    message = "Plugin execution failed and fused: ${e.message}",
                    httpStatus = 500
                )
            )
        } finally {
            callCounter.decrementAndGet()
        }
    }

    override fun shutdown() {
        status = PluginStatus.DISABLED
        onShutdown()
    }
}

/**
 * 动态插件宿主协调器 (Native Plugin Host)
 * 负责管理插件安装包、执行 SHA256 完整性核查、沙箱管理与调度路由
 */
class NativePluginHost(
    private val context: Context
) {
    private val pluginsDir = File(context.codeCacheDir, "plugins").apply { mkdirs() }
    private val registeredPlugins = ConcurrentHashMap<String, IPluginClient>()

    /**
     * 校验字节数组的 SHA-256 完整性哈希
     */
    fun verifySha256(data: ByteArray, expectedSha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        val calculatedHex = hash.joinToString("") { "%02x".format(it) }
        return calculatedHex.equals(expectedSha256.trim(), ignoreCase = true)
    }

    /**
     * 注册或加载插件
     */
    fun registerPlugin(manifest: PluginManifest, client: IPluginClient) {
        if (!manifest.isValid()) return
        registeredPlugins[manifest.id] = client
    }

    /**
     * 获取全部已加载的插件
     */
    fun getAllPlugins(): List<IPluginClient> {
        return registeredPlugins.values.toList()
    }

    /**
     * 获取指定 ID 的插件
     */
    fun getPlugin(id: String): IPluginClient? {
        return registeredPlugins[id]
    }

    /**
     * 启用或禁用插件
     */
    fun setPluginEnabled(id: String, enabled: Boolean) {
        val client = registeredPlugins[id] ?: return
        client.status = if (enabled) PluginStatus.ACTIVE else PluginStatus.DISABLED
    }

    /**
     * 重置熔断状态
     */
    fun resetPluginFuse(id: String) {
        val client = registeredPlugins[id] ?: return
        if (client.status == PluginStatus.FUSED_CRASHED) {
            client.status = PluginStatus.ACTIVE
        }
    }

    /**
     * 卸载并释放插件
     */
    fun unloadPlugin(id: String) {
        val client = registeredPlugins.remove(id) ?: return
        client.shutdown()
        val pluginDir = File(pluginsDir, id)
        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
    }

    /**
     * 广播请求前置拦截
     */
    suspend fun interceptBefore(requestJson: String): String = withContext(Dispatchers.IO) {
        var currentJson = requestJson
        for (client in registeredPlugins.values) {
            if (client.status == PluginStatus.ACTIVE &&
                client.manifest.capabilities.contains(PluginCapability.REQUEST_INTERCEPTOR)
            ) {
                val envelope = client.call("request.intercept_before", currentJson)
                if (envelope.ok && envelope.result != null) {
                    currentJson = envelope.result.toString()
                }
            }
        }
        currentJson
    }
}
