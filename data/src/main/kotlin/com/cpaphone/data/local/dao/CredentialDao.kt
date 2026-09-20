package com.cpaphone.data.local.dao

import androidx.room.*
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.data.local.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: String): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: CredentialEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CredentialEntity>)

    @Query("UPDATE credentials SET status = :status, statusMessage = :message WHERE id = :id")
    suspend fun updateStatus(id: String, status: CredentialStatus, message: String)

    @Query("UPDATE credentials SET totalRequests = totalRequests + 1, successfulRequests = successfulRequests + :successIncrement WHERE id = :id")
    suspend fun recordRequestMetrics(id: String, successIncrement: Int)

    @Query("UPDATE credentials SET lastErrorTimestamp = :timestamp, lastErrorMessage = :errorMessage WHERE id = :id")
    suspend fun recordError(id: String, timestamp: Long, errorMessage: String)

    @Delete
    suspend fun delete(entity: CredentialEntity)

    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: String)
}
