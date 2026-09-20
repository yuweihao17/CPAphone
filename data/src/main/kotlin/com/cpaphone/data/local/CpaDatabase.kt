package com.cpaphone.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cpaphone.data.local.dao.CredentialDao
import com.cpaphone.data.local.dao.TraceLogDao
import com.cpaphone.data.local.entity.CredentialEntity
import com.cpaphone.data.local.entity.TraceLogEntity

@Database(
    entities = [CredentialEntity::class, TraceLogEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CpaDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun traceLogDao(): TraceLogDao

    companion object {
        @Volatile
        private var INSTANCE: CpaDatabase? = null

        fun getInstance(context: Context): CpaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CpaDatabase::class.java,
                    "cpaphone.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
