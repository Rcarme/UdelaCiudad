package com.tgmon.app.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = Migration(4, 5) { db ->
    db.execSQL("""
        CREATE TABLE IF NOT EXISTS container_cache (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            status TEXT NOT NULL,
            image TEXT NOT NULL,
            cpu_percent REAL NOT NULL DEFAULT 0.0,
            memory_mb REAL NOT NULL DEFAULT 0.0,
            memory_percent REAL NOT NULL DEFAULT 0.0,
            uptime_seconds INTEGER NOT NULL DEFAULT 0,
            health_status TEXT NOT NULL DEFAULT 'none',
            network_rx_mb REAL NOT NULL DEFAULT 0.0,
            network_tx_mb REAL NOT NULL DEFAULT 0.0,
            timestamp INTEGER NOT NULL DEFAULT 0
        )
    """)
}