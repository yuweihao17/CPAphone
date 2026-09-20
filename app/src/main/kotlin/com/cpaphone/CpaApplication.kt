package com.cpaphone

import android.app.Application
import com.cpaphone.data.local.CpaDatabase
import com.cpaphone.data.repository.AppConfigRepository
import com.cpaphone.data.repository.CredentialRepository
import com.cpaphone.data.security.SecureCredentialStorage
import com.cpaphone.engine.coordinator.CredentialCoordinator
import com.cpaphone.engine.discovery.NsdDiscoveryManager
import com.cpaphone.engine.plugin.NativePluginHost
import com.cpaphone.engine.remote.RemoteManagementClient
import com.cpaphone.engine.server.LocalProxyServer
import com.cpaphone.engine.service.CpaProxyService

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

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = CpaDatabase.getInstance(this)
        secureStorage = SecureCredentialStorage(this)
        credentialRepository = CredentialRepository(database.credentialDao(), secureStorage)
        appConfigRepository = AppConfigRepository(this)

        coordinator = CredentialCoordinator(credentialRepository)
        localProxyServer = LocalProxyServer(
            coordinator = coordinator,
            traceLogDao = database.traceLogDao(),
            credentialRepository = credentialRepository
        )
        remoteClient = RemoteManagementClient("http://127.0.0.1:8317", "")
        nsdDiscoveryManager = NsdDiscoveryManager(this)
        nativePluginHost = NativePluginHost(this)

        // 绑定静态服务实例
        CpaProxyService.activeServerInstance = localProxyServer
    }

    companion object {
        lateinit var instance: CpaApplication
            private set
    }
}
