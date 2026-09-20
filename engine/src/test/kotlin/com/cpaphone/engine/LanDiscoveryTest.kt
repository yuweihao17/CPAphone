package com.cpaphone.engine

import com.cpaphone.core.model.LanDeviceNode
import com.cpaphone.core.model.LanDiscoveryConstants
import org.junit.Assert.*
import org.junit.Test

class LanDiscoveryTest {

    @Test
    fun testLanDeviceNodeProperties() {
        val node = LanDeviceNode(
            id = "node-101",
            name = "MacBook-Air-CPA",
            hostAddress = "192.168.1.120",
            port = 8317,
            isSelf = false,
            isSafeMode = true
        )

        assertEquals("http://192.168.1.120:8317", node.baseUrl)
        assertFalse(node.isSelf)
        assertTrue(node.isSafeMode)
        assertFalse(node.isExpired)
    }

    @Test
    fun testLanDiscoveryConstantsProtocol() {
        assertEquals("_cpaproxy._tcp", LanDiscoveryConstants.SERVICE_TYPE)
        assertEquals("id", LanDiscoveryConstants.TXT_KEY_ID)
        assertEquals("name", LanDiscoveryConstants.TXT_KEY_NAME)
        assertEquals("safe", LanDiscoveryConstants.TXT_KEY_SAFE_MODE)
    }

    @Test
    fun testExpiredNodeDetection() {
        val oldTimestamp = System.currentTimeMillis() - 70_000L // 70 秒前
        val expiredNode = LanDeviceNode(
            id = "node-old",
            name = "Old-Node",
            hostAddress = "192.168.1.200",
            port = 8317,
            discoveredAt = oldTimestamp
        )

        assertTrue(expiredNode.isExpired)
    }
}
