package com.cpaphone.core.model

import kotlinx.serialization.Serializable

/**
 * 局域网服务发现节点模型 (mDNS / NSD)
 * 对应 _cpaproxy._tcp 广播的节点信息
 */
@Serializable
data class LanDeviceNode(
    val id: String,                    // 节点唯一 UUID 或设备标识
    val name: String,                  // 友好设备名 (如 "MacBook-Pro-CLIProxyAPI" 或 "Pixel-8-CPAphone")
    val hostAddress: String,           // 局域网 IPv4 地址 (如 "192.168.1.102")
    val port: Int,                     // 代理监听端口 (默认 8317)
    val isSelf: Boolean = false,       // 是否为当前手机本地自身的广播
    val isSafeMode: Boolean = true,    // 该节点是否激活了 Safe Mode
    val protocolVersion: Int = 1,      // 发现协议版本
    val discoveredAt: Long = System.currentTimeMillis()
) {
    val baseUrl: String
        get() = "http://$hostAddress:$port"

    val isExpired: Boolean
        get() = (System.currentTimeMillis() - discoveredAt) > 60_000L // 60 秒未刷新视为下线
}

/**
 * mDNS TXT 记录常量规范
 */
object LanDiscoveryConstants {
    const val SERVICE_TYPE = "_cpaproxy._tcp"
    const val DEFAULT_SERVICE_NAME = "CPAphone-Node"
    const val TXT_KEY_ID = "id"
    const val TXT_KEY_NAME = "name"
    const val TXT_KEY_VERSION = "v"
    const val TXT_KEY_SAFE_MODE = "safe"
}
