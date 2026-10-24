package com.tgmon.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasRamPercent = db.query("PRAGMA table_info(metrics)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == "ram_percent") return@use true
            }
            false
        }
        
        if (!hasRamPercent) {
            db.execSQL("ALTER TABLE metrics ADD COLUMN ram_percent REAL DEFAULT 0.0")
        }
        
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS metric_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                hostname TEXT NOT NULL DEFAULT '',
                cpu_percent REAL NOT NULL DEFAULT 0.0,
                memory_percent REAL NOT NULL DEFAULT 0.0,
                disk_used_gb REAL NOT NULL DEFAULT 0.0,
                disk_free_gb REAL NOT NULL DEFAULT 0.0,
                disk_total_gb REAL NOT NULL DEFAULT 0.0,
                disk_percent REAL NOT NULL DEFAULT 0.0
            )
        """)
        
        db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_history_timestamp ON metric_history(timestamp)")
        
        db.execSQL("""
            INSERT OR IGNORE INTO metric_history 
            (id, timestamp, hostname, cpu_percent, memory_percent, disk_used_gb, disk_free_gb, disk_total_gb, disk_percent) 
            SELECT id, timestamp, hostname, cpu_percent, COALESCE(ram_percent, 0), disk_used_gb, disk_free_gb, disk_total_gb, 
            CASE WHEN disk_total_gb IS NOT NULL AND disk_total_gb > 0 THEN (disk_used_gb / disk_total_gb) * 100 ELSE 0 END
            FROM metrics
        """)

        db.execSQL("DROP TABLE IF EXISTS metrics")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS container_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                container_id TEXT NOT NULL,
                container_name TEXT NOT NULL DEFAULT '',
                logs TEXT NOT NULL,
                timestamp INTEGER NOT NULL DEFAULT 0
            )
        """)
        
        db.execSQL("CREATE INDEX IF NOT EXISTS index_container_logs_container_id ON container_logs(container_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_container_logs_timestamp ON container_logs(timestamp)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasDiskPercent = db.query("PRAGMA table_info(metric_history)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                val colName = if (nameIndex >= 0) cursor.getString(nameIndex) else cursor.getString(1)
                if (colName == "disk_percent") return@use true
            }
            false
        }
        if (!hasDiskPercent) {
            db.execSQL("ALTER TABLE metric_history ADD COLUMN disk_percent REAL NOT NULL DEFAULT 0.0")
        }
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS alert_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                message TEXT NOT NULL,
                value REAL NOT NULL DEFAULT 0.0,
                server_id TEXT NOT NULL DEFAULT '',
                container_id TEXT)
        """)
    }
}
