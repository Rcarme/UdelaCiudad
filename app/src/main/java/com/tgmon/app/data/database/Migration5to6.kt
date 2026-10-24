package com.tgmon.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_5_6 = Migration(5, 6) { db ->
    // Drop old/conflicting index names if they existed from previous attempts
    db.execSQL("DROP INDEX IF EXISTS idx_metric_history_timestamp")
    db.execSQL("DROP INDEX IF EXISTS idx_container_logs_container_id")
    db.execSQL("DROP INDEX IF EXISTS idx_container_logs_timestamp")
    db.execSQL("DROP INDEX IF EXISTS idx_container_cache_timestamp")
    db.execSQL("DROP INDEX IF EXISTS idx_alert_history_timestamp")
    db.execSQL("DROP INDEX IF EXISTS idx_alert_history_server_id")

    // Create exact indices matching Room entities
    db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_history_timestamp ON metric_history(timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_container_logs_timestamp ON container_logs(timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_container_logs_container_id ON container_logs(container_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_container_logs_container_id_timestamp ON container_logs(container_id, timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_history_timestamp ON alert_history(timestamp)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_alert_history_server_id_timestamp ON alert_history(server_id, timestamp)")
}