package com.cpaphone.core.model

import kotlinx.serialization.Serializable

/**
 * 远程云端存储后端类型
 */
@Serializable
enum class RemoteStoreType {
    LOCAL_FILE,    // 本地文件与 SQLite (默认)
    POSTGRES,      // PostgreSQL 关系型数据库镜像
    GIT,           // GitStore 版本控制同步
    S3_OBJECT,     // S3 / MinIO 对象存储备份
    HOME_CLUSTER   // Home 集中控制中心模式 (基于 Redis RESP 协议)
}

/**
 * 存储数据同步快照
 */
@Serializable
data class StoreSyncSnapshot(
    val snapshotVersion: Long = System.currentTimeMillis(),
    val credentials: List<AuthCredential> = emptyList(),
    val configYaml: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 统一存储驱动契约
 */
interface StoreAdapter {
    val storeType: RemoteStoreType

    suspend fun listCredentials(): List<AuthCredential>
    suspend fun saveCredential(credential: AuthCredential): Boolean
    suspend fun deleteCredential(credentialId: String): Boolean
    suspend fun pullSnapshot(): StoreSyncSnapshot
    suspend fun pushSnapshot(snapshot: StoreSyncSnapshot): Boolean
}
