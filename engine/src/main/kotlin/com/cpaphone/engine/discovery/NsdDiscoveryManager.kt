package com.cpaphone.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.cpaphone.core.model.LanDeviceNode
import com.cpaphone.core.model.LanDiscoveryConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网服务广播与设备雷达发现引擎 (Android Network Service Discovery)
 * 1. 负责注册 _cpaproxy._tcp 本机 mDNS 广播，让局域网内 PC 客户端/终端一键发现；
 * 2. 负责持续监听同网段运行中的 CLIProxyAPI 节点与 CPAphone 节点并解析为可用列表。
 */
class NsdDiscoveryManager(
    private val context: Context,
    val localNodeId: String = UUID.randomUUID().toString().substring(0, 8)
) {
    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val discoveredMap = ConcurrentHashMap<String, LanDeviceNode>()
    private val _discoveredNodes = MutableStateFlow<List<LanDeviceNode>>(emptyList())
    val discoveredNodes: StateFlow<List<LanDeviceNode>> = _discoveredNodes.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isRegistered = false
    private var isDiscovering = false

    /**
     * 注册本机局域网 mDNS 广播
     */
    fun registerService(port: Int, deviceName: String = "Pixel-CPAphone", isSafeMode: Boolean = true) {
        if (isRegistered || nsdManager == null) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${LanDiscoveryConstants.DEFAULT_SERVICE_NAME}-$localNodeId"
            serviceType = LanDiscoveryConstants.SERVICE_TYPE
            this.port = port

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute(LanDiscoveryConstants.TXT_KEY_ID, localNodeId)
                setAttribute(LanDiscoveryConstants.TXT_KEY_NAME, deviceName)
                setAttribute(LanDiscoveryConstants.TXT_KEY_VERSION, "1")
                setAttribute(LanDiscoveryConstants.TXT_KEY_SAFE_MODE, if (isSafeMode) "1" else "0")
            }
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                isRegistered = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                isRegistered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (_: Exception) {
        }
    }

    /**
     * 停止本机 mDNS 广播
     */
    fun unregisterService() {
        if (!isRegistered || nsdManager == null || registrationListener == null) return
        try {
            nsdManager?.unregisterService(registrationListener)
        } catch (_: Exception) {
        } finally {
            isRegistered = false
            registrationListener = null
        }
    }

    /**
     * 启动局域网同网段节点扫描雷达
     */
    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains(LanDiscoveryConstants.SERVICE_TYPE.trimStart('.'))) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val key = service.serviceName
                discoveredMap.remove(key)
                updateStateFlow()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
            }
        }

        try {
            nsdManager?.discoverServices(
                LanDiscoveryConstants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 停止局域网扫描
     */
    fun stopDiscovery() {
        if (!isDiscovering || nsdManager == null || discoveryListener == null) return
        try {
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) {
        } finally {
            isDiscovering = false
            discoveryListener = null
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val host = resolvedInfo.host?.hostAddress ?: return
                val port = resolvedInfo.port

                var nodeId = resolvedInfo.serviceName
                var nodeName = resolvedInfo.serviceName
                var isSafe = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attrs = resolvedInfo.attributes
                    attrs[LanDiscoveryConstants.TXT_KEY_ID]?.let {
                        nodeId = String(it, StandardCharsets.UTF_8)
                    }
                    attrs[LanDiscoveryConstants.TXT_KEY_NAME]?.let {
                        nodeName = String(it, StandardCharsets.UTF_8)
                    }
                    attrs[LanDiscoveryConstants.TXT_KEY_SAFE_MODE]?.let {
                        isSafe = String(it, StandardCharsets.UTF_8) == "1"
                    }
                }

                val isSelf = (nodeId == localNodeId)

                val node = LanDeviceNode(
                    id = nodeId,
                    name = nodeName,
                    hostAddress = host,
                    port = port,
                    isSelf = isSelf,
                    isSafeMode = isSafe
                )

                // 过滤自身并不重复添加
                if (!isSelf) {
                    discoveredMap[node.id] = node
                    updateStateFlow()
                }
            }
        }

        try {
            nsdManager?.resolveService(serviceInfo, resolver)
        } catch (_: Exception) {
        }
    }

    private fun updateStateFlow() {
        // 过滤过期节点
        val now = System.currentTimeMillis()
        discoveredMap.entries.removeIf { (now - it.value.discoveredAt) > 60_000L }
        _discoveredNodes.value = discoveredMap.values.toList()
    }
}
