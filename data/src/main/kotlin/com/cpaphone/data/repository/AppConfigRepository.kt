package com.cpaphone.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.cpaphone.core.model.RoutingStrategyType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cpa_app_config")

@Serializable
enum class RunningMode {
    LOCAL_PROXY,        // 本地独立代理模式
    REMOTE_MANAGEMENT   // 远程中控管理模式
}

@Serializable
data class AppConfig(
    val runningMode: RunningMode = RunningMode.LOCAL_PROXY,
    val localPort: Int = 8317,
    val allowLanAccess: Boolean = false,
    val routingStrategy: RoutingStrategyType = RoutingStrategyType.WEIGHTED_ROUND_ROBIN,
    val safeModeEnabled: Boolean = true,
    val enableCloaking: Boolean = true,          // 客户端指纹伪装与请求披风开关
    val enableLanDiscovery: Boolean = true,      // 局域网服务广播与设备雷达发现开关
    val apiKeys: List<String> = emptyList(),     // 网关鉴权 api-keys（空列表=不启用鉴权）
    val connectTimeoutMs: Long = 15_000L,        // 上游连接超时 (毫秒)
    val requestTimeoutMs: Long = 120_000L,       // 上游总请求超时 (毫秒)
    val outboundProxyUrl: String? = null,
    val remoteHostUrl: String = "http://192.168.1.100:8317",
    val remoteSecretKey: String = "",
    val sessionAffinityEnabled: Boolean = true,
    val sessionAffinityTtlSeconds: Int = 3600
)

class AppConfigRepository(private val context: Context) {

    private object PreferencesKeys {
        val RUNNING_MODE = stringPreferencesKey("running_mode")
        val LOCAL_PORT = intPreferencesKey("local_port")
        val ALLOW_LAN_ACCESS = booleanPreferencesKey("allow_lan_access")
        val ROUTING_STRATEGY = stringPreferencesKey("routing_strategy")
        val SAFE_MODE_ENABLED = booleanPreferencesKey("safe_mode_enabled")
        val ENABLE_CLOAKING = booleanPreferencesKey("enable_cloaking")
        val ENABLE_LAN_DISCOVERY = booleanPreferencesKey("enable_lan_discovery")
        val API_KEYS = stringPreferencesKey("api_keys")
        val CONNECT_TIMEOUT_MS = longPreferencesKey("connect_timeout_ms")
        val REQUEST_TIMEOUT_MS = longPreferencesKey("request_timeout_ms")
        val OUTBOUND_PROXY_URL = stringPreferencesKey("outbound_proxy_url")
        val REMOTE_HOST_URL = stringPreferencesKey("remote_host_url")
        val REMOTE_SECRET_KEY = stringPreferencesKey("remote_secret_key")
        val SESSION_AFFINITY_ENABLED = booleanPreferencesKey("session_affinity_enabled")
        val SESSION_AFFINITY_TTL = intPreferencesKey("session_affinity_ttl")
    }

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            runningMode = prefs[PreferencesKeys.RUNNING_MODE]?.let {
                try { RunningMode.valueOf(it) } catch (_: Exception) { RunningMode.LOCAL_PROXY }
            } ?: RunningMode.LOCAL_PROXY,
            localPort = prefs[PreferencesKeys.LOCAL_PORT] ?: 8317,
            allowLanAccess = prefs[PreferencesKeys.ALLOW_LAN_ACCESS] ?: false,
            routingStrategy = prefs[PreferencesKeys.ROUTING_STRATEGY]?.let {
                try { RoutingStrategyType.valueOf(it) } catch (_: Exception) { RoutingStrategyType.WEIGHTED_ROUND_ROBIN }
            } ?: RoutingStrategyType.WEIGHTED_ROUND_ROBIN,
            safeModeEnabled = prefs[PreferencesKeys.SAFE_MODE_ENABLED] ?: true,
            enableCloaking = prefs[PreferencesKeys.ENABLE_CLOAKING] ?: true,
            enableLanDiscovery = prefs[PreferencesKeys.ENABLE_LAN_DISCOVERY] ?: true,
            apiKeys = prefs[PreferencesKeys.API_KEYS]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            connectTimeoutMs = prefs[PreferencesKeys.CONNECT_TIMEOUT_MS] ?: 15_000L,
            requestTimeoutMs = prefs[PreferencesKeys.REQUEST_TIMEOUT_MS] ?: 120_000L,
            outboundProxyUrl = prefs[PreferencesKeys.OUTBOUND_PROXY_URL],
            remoteHostUrl = prefs[PreferencesKeys.REMOTE_HOST_URL] ?: "http://192.168.1.100:8317",
            remoteSecretKey = prefs[PreferencesKeys.REMOTE_SECRET_KEY] ?: "",
            sessionAffinityEnabled = prefs[PreferencesKeys.SESSION_AFFINITY_ENABLED] ?: true,
            sessionAffinityTtlSeconds = prefs[PreferencesKeys.SESSION_AFFINITY_TTL] ?: 3600
        )
    }

    suspend fun updateRunningMode(mode: RunningMode) {
        context.dataStore.edit { it[PreferencesKeys.RUNNING_MODE] = mode.name }
    }

    suspend fun updateLocalPort(port: Int) {
        context.dataStore.edit { it[PreferencesKeys.LOCAL_PORT] = port }
    }

    suspend fun updateAllowLanAccess(allow: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ALLOW_LAN_ACCESS] = allow }
    }

    suspend fun updateRoutingStrategy(strategy: RoutingStrategyType) {
        context.dataStore.edit { it[PreferencesKeys.ROUTING_STRATEGY] = strategy.name }
    }

    suspend fun updateSafeMode(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SAFE_MODE_ENABLED] = enabled }
    }

    suspend fun updateCloaking(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ENABLE_CLOAKING] = enabled }
    }

    suspend fun updateLanDiscovery(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.ENABLE_LAN_DISCOVERY] = enabled }
    }

    suspend fun updateApiKeys(keys: List<String>) {
        context.dataStore.edit {
            val normalized = keys.map { it.trim() }.filter { it.isNotEmpty() }
            if (normalized.isEmpty()) {
                it.remove(PreferencesKeys.API_KEYS)
            } else {
                it[PreferencesKeys.API_KEYS] = normalized.joinToString(",")
            }
        }
    }

    suspend fun updateTimeouts(connectTimeoutMs: Long, requestTimeoutMs: Long) {
        context.dataStore.edit {
            it[PreferencesKeys.CONNECT_TIMEOUT_MS] = connectTimeoutMs
            it[PreferencesKeys.REQUEST_TIMEOUT_MS] = requestTimeoutMs
        }
    }

    suspend fun updateOutboundProxy(url: String?) {
        context.dataStore.edit {
            if (url.isNullOrBlank()) {
                it.remove(PreferencesKeys.OUTBOUND_PROXY_URL)
            } else {
                it[PreferencesKeys.OUTBOUND_PROXY_URL] = url
            }
        }
    }

    suspend fun updateRemoteConnection(hostUrl: String, secretKey: String) {
        context.dataStore.edit {
            it[PreferencesKeys.REMOTE_HOST_URL] = hostUrl
            it[PreferencesKeys.REMOTE_SECRET_KEY] = secretKey
        }
    }
}
