package com.cpaphone.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cpaphone.data.local.entity.CredentialQuotaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialQuotaDao {
    @Query("SELECT * FROM credential_quotas")
    fun getAllFlow(): Flow<List<CredentialQuotaEntity>>

    @Query("SELECT * FROM credential_quotas WHERE credentialId = :credentialId")
    fun getByIdFlow(credentialId: String): Flow<CredentialQuotaEntity?>

    @Query("SELECT * FROM credential_quotas WHERE credentialId = :credentialId")
    suspend fun getById(credentialId: String): CredentialQuotaEntity?

    @Query("SELECT * FROM credential_quotas")
    suspend fun getAll(): List<CredentialQuotaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: CredentialQuotaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CredentialQuotaEntity>)

    @Query("DELETE FROM credential_quotas WHERE credentialId = :credentialId")
    suspend fun deleteById(credentialId: String)
}
