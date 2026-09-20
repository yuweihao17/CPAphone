package com.cpaphone.core.model

import kotlinx.serialization.Serializable

/**
 * 在途请求聚合指标
 */
@Serializable
data class InFlightAggregate(
    val credentialId: String,
    val model: String,
    val status: String = "accounted",
    val count: Long
)

/**
 * 在途请求快照数据帧 (In-Flight Snapshot Frame)
 */
@Serializable
data class InFlightSnapshotFrame(
    val kind: String = "part", // "part" 或 "overflow"
    val revision: Long,
    val observedAt: Long = System.currentTimeMillis(),
    val barrierRevision: Long = 0L,
    val partIndex: Int? = null,
    val partCount: Int? = null,
    val aggregates: List<InFlightAggregate> = emptyList(),
    val aggregateGroupCount: Int = aggregates.size
)

/**
 * 单调递增累计并发释放帧 (Concurrency Release Frame)
 * 采用单调累计序列号代替简单的 +/-1 计数器，解决网络乱序重试导致的并发槽泄露与死锁
 */
@Serializable
data class ConcurrencyReleaseFrame(
    val credentialId: String,
    val model: String,
    val releaseSeq: Long
)

/**
 * Home 集群状态枚举
 */
@Serializable
enum class HomeConnectionState {
    DISCONNECTED,
    CONNECTING,
    ENROLLED_ACTIVE,
    FENCED_BLOCKED
}
