package com.tgmon.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tgmon.app.data.models.AlertHistory
import com.tgmon.app.data.models.ContainerCache
import com.tgmon.app.data.models.ContainerLog
import com.tgmon.app.data.models.MetricHistory

@Database(entities = [MetricHistory::class, ContainerLog::class, AlertHistory::class, ContainerCache::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metricHistoryDao(): MetricHistoryDao
    abstract fun containerLogDao(): ContainerLogDao
    abstract fun alertHistoryDao(): AlertHistoryDao
    abstract fun containerCacheDao(): ContainerCacheDao

    override fun close() {
        super.close()
        synchronized(AppDatabase::class.java) {
            if (instance === this) {
                instance = null
            }
        }
    }
    
    companion object {
        @Volatile
        private var instance: AppDatabase? = null
        
        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tgmon_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
