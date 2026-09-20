package com.cpaphone

import android.app.Application
import com.cpaphone.core.session.OAuthSessionManager
import com.cpaphone.data.local.CpaDatabase
import com.cpaphone.data.repository.AppConfigRepository
import com.cpaphone.data.repository.CredentialRepository
import com.cpaphone.data.security.SecureCredentialStorage
import com.cpaphone.engine.coordinator.CredentialCoordinator
import com.cpaphone.engine.discovery.NsdDiscoveryManager
import com.cpaphone.engine.oauth.OAuthLoginManager
import com.cpaphone.engine.plugin.NativePluginHost
import com.cpaphone.engine.remote.RemoteManagementClient
import com.cpaphone.engine.server.LocalProxyServer
import com.cpaphone.engine.service.CpaProxyService
import kotlinx.coroutines.launch

/**
 * 全局应用上下文与轻量级单例组装器 (Zero Overhead DI)
 */
class CpaApplication : Application() {

    lateinit var database: CpaDatabase
        private set
    lateinit var secureStorage: SecureCredentialStorage
        private set
    lateinit var credentialRepository: CredentialRepository
        private set
    lateinit var appConfigRepository: AppConfigRepository
        private set
    lateinit var coordinator: CredentialCoordinator
        private set
    lateinit var localProxyServer: LocalProxyServer
        private set
    lateinit var remoteClient: RemoteManagementClient
        private set
    lateinit var nsdDiscoveryManager: NsdDiscoveryManager
        private set
    lateinit var nativePluginHost: NativePluginHost
        private set
    lateinit var oauthSessionManager: OAuthSessionManager
        private set
    lateinit var oauthLoginManager: OAuthLoginManager
        private set
    lateinit var oauthQuotaEngine: com.cpaphone.engine.quota.OAuthQuotaEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = CpaDatabase.getInstance(this)
        secureStorage = SecureCredentialStorage(this)
        credentialRepository = CredentialRepository(
            dao = database.credentialDao(),
            secureStorage = secureStorage,
            quotaDao = database.credentialQuotaDao()
        )
        appConfigRepository = AppConfigRepository(this)

        oauthSessionManager = OAuthSessionManager()
        oauthLoginManager = OAuthLoginManager(oauthSessionManager, credentialRepository)
        oauthQuotaEngine = com.cpaphone.engine.quota.OAuthQuotaEngine()
        coordinator = CredentialCoordinator(credentialRepository, oauthLoginManager)
        localProxyServer = LocalProxyServer(
            coordinator = coordinator,
            traceLogDao = database.traceLogDao(),
            credentialRepository = credentialRepository,
            oauthSessionManager = oauthSessionManager,
            oauthLoginManager = oauthLoginManager
        )
        remoteClient = RemoteManagementClient("http://127.0.0.1:8317", "")
        nsdDiscoveryManager = NsdDiscoveryManager(this)
        nativePluginHost = NativePluginHost(this)

        // 网关鉴权 api-keys 接线：后台常驻收集配置流，内存缓存实时生效
        val apiKeysCache = java.util.concurrent.atomic.AtomicReference<Set<String>>(emptySet())
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            appConfigRepository.configFlow.collect { apiKeysCache.set(it.apiKeys.toSet()) }
        }
        localProxyServer.setApiKeysProvider { apiKeysCache.get() }

        // 绑定静态服务实例
        CpaProxyService.activeServerInstance = localProxyServer
    }

    companion object {
        lateinit var instance: CpaApplication
            private set
    }
}
