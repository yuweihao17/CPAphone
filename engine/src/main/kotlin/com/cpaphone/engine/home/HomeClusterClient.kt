package com.cpaphone.engine.home

import com.cpaphone.core.model.ConcurrencyReleaseFrame
import com.cpaphone.core.model.HomeConnectionState
import com.cpaphone.core.model.InFlightAggregate
import com.cpaphone.core.model.InFlightSnapshotFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Home 集中调度控制平面客户端 (Home Cluster Client)
 * 对齐 CLIProxyAPI internal/home/ 架构体系：
 * 1. 管理节点与 Home 控制中心的 mTLS / RESP 协议连接状态；
 * 2. 负责累计并发释放刷新器 (ReleaseFlusher)：单调序列号防泄漏；
 * 3. 负责周期在途请求快照打包 (In-Flight Snapshot Bin-Packing)。
 */
class HomeClusterClient {

    private val _connectionState = MutableStateFlow(HomeConnectionState.DISCONNECTED)
    val connectionState: StateFlow<HomeConnectionState> = _connectionState.asStateFlow()

    // 并发释放器状态: (credential_id#model) -> 最新累计序号
    private val releaseLatestMap = ConcurrentHashMap<String, AtomicLong>()
    private val releaseAckedMap = ConcurrentHashMap<String, Long>()

    private var currentSnapshotRevision = 1L

    /**
     * 模拟启动并连接到 Home 集群
     */
    fun connect(endpoint: String, jwtToken: String) {
        _connectionState.value = HomeConnectionState.CONNECTING
        // 模拟 mTLS 握手与 JWT 认证完成
        _connectionState.value = HomeConnectionState.ENROLLED_ACTIVE
    }

    /**
     * 断开集群连接
     */
    fun disconnect() {
        _connectionState.value = HomeConnectionState.DISCONNECTED
    }

    /**
     * 标记请求完成，递增累计并发释放序列号 (ReleaseFlusher.MarkDirty)
     */
    fun markReleaseDirty(credentialId: String, model: String): ConcurrencyReleaseFrame {
        val groupKey = "$credentialId#$model"
        val counter = releaseLatestMap.computeIfAbsent(groupKey) { AtomicLong(0) }
        val newSeq = counter.incrementAndGet()

        return ConcurrencyReleaseFrame(
            credentialId = credentialId,
            model = model,
            releaseSeq = newSeq
        )
    }

    /**
     * 确认并 ACK 已成功上报的并发释放序号 (幂等语义)
     */
    fun ackRelease(credentialId: String, model: String, ackSeq: Long) {
        val groupKey = "$credentialId#$model"
        val currentAcked = releaseAckedMap[groupKey] ?: 0L
        if (ackSeq > currentAcked) {
            releaseAckedMap[groupKey] = ackSeq
        }
    }

    /**
     * 获取未决的并发释放帧列表 (Latest > Acked)
     */
    fun getPendingReleaseFrames(): List<ConcurrencyReleaseFrame> {
        val pending = mutableListOf<ConcurrencyReleaseFrame>()
        releaseLatestMap.forEach { (groupKey, counter) ->
            val latest = counter.get()
            val acked = releaseAckedMap[groupKey] ?: 0L
            if (latest > acked) {
                val parts = groupKey.split("#", limit = 2)
                pending.add(
                    ConcurrencyReleaseFrame(
                        credentialId = parts[0],
                        model = parts.getOrNull(1) ?: "",
                        releaseSeq = latest
                    )
                )
            }
        }
        return pending
    }

    /**
     * 在途请求快照打包算法 (Bin-Packing)
     * 当聚合分组超过限制时，优雅降级为 overflow 帧
     */
    fun packInFlightSnapshot(
        activeRequests: List<Pair<String, String>>, // (credentialId, model) 列表
        maxGroups: Int = 100
    ): InFlightSnapshotFrame {
        val revision = currentSnapshotRevision++
        val groupMap = mutableMapOf<Pair<String, String>, Long>()

        activeRequests.forEach { req ->
            val count = groupMap.getOrDefault(req, 0L)
            groupMap[req] = count + 1L
        }

        if (groupMap.size > maxGroups) {
            // 溢出降级帧
            return InFlightSnapshotFrame(
                kind = "overflow",
                revision = revision,
                observedAt = System.currentTimeMillis(),
                aggregateGroupCount = groupMap.size
            )
        }

        val aggregates = groupMap.map { (key, count) ->
            InFlightAggregate(
                credentialId = key.first,
                model = key.second,
                count = count
            )
        }.sortedWith(compareBy({ it.credentialId }, { it.model }))

        return InFlightSnapshotFrame(
            kind = "part",
            revision = revision,
            observedAt = System.currentTimeMillis(),
            aggregates = aggregates,
            aggregateGroupCount = aggregates.size
        )
    }
}
