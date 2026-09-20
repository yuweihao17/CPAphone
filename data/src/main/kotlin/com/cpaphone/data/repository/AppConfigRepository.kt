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
