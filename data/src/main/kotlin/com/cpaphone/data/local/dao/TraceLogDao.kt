package com.cpaphone.data.local.dao

import androidx.room.*
import com.cpaphone.data.local.entity.TraceLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TraceLogDao {
    @Query("SELECT * FROM trace_logs ORDER BY timestamp DESC LIMIT 500")
    fun getRecentLogsFlow(): Flow<List<TraceLogEntity>>

    @Query("SELECT * FROM trace_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 100): List<TraceLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TraceLogEntity)

    @Query("DELETE FROM trace_logs WHERE traceId NOT IN (SELECT traceId FROM trace_logs ORDER BY timestamp DESC LIMIT 500)")
    suspend fun pruneOldLogs()

    @Query("DELETE FROM trace_logs")
    suspend fun clearAll()
}
