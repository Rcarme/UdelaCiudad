import asyncio
import os
import logging
import sqlite3
import time
import threading

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("tgmon")
DB_PATH = os.getenv("TGMON_DB_PATH", "metrics.db")

_local = threading.local()
_db_lock = threading.Lock()

def get_connection():
    # N97: thread-local sqlite3 kept instead of aiosqlite to avoid extra dependency/
    # event-loop overhead on low-CPU device. WAL + busy_timeout keep concurrent
    # async callers (via asyncio.to_thread) from blocking writers.
    if not hasattr(_local, 'connection') or _local.connection is None:
        _local.connection = sqlite3.connect(DB_PATH, timeout=30.0, isolation_level=None)
        _local.connection.row_factory = sqlite3.Row
        _local.connection.execute("PRAGMA busy_timeout = 30000")
    return _local.connection

def close_connection():
    if hasattr(_local, 'connection') and _local.connection is not None:
        _local.connection.close()
        _local.connection = None

def init_db():
    conn = get_connection()
    cursor = conn.cursor()

    # N97: WAL + NORMAL + 64MB cache improves concurrent read/write on low-resource
    # without migrating to aiosqlite (keeps sqlite3 thread-local, calls from async
    # must use await asyncio.to_thread(...)).
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA synchronous=NORMAL")
    cursor.execute("PRAGMA cache_size=-64000")  # 64MB cache

    cursor.execute('''
        CREATE TABLE IF NOT EXISTS metrics (
            timestamp INTEGER PRIMARY KEY,
            cpu_percent REAL,
            memory_percent REAL,
            disk_used_gb REAL,
            disk_free_gb REAL,
            disk_total_gb REAL,
            disk_percent REAL
        )
    ''')
    try:
        cursor.execute("PRAGMA table_info(metrics)")
        columns = {row[1] for row in cursor.fetchall()}
        
        if 'ram_percent' in columns and 'memory_percent' not in columns:
            cursor.execute('ALTER TABLE metrics RENAME COLUMN ram_percent TO memory_percent')
            conn.commit()
        elif 'ram_percent' not in columns and 'memory_percent' not in columns:
            cursor.execute('ALTER TABLE metrics ADD COLUMN memory_percent REAL DEFAULT 0.0')
            conn.commit()
        else:
            logger.info("Column memory_percent already exists")
    except Exception as e:
        if isinstance(e, sqlite3.OperationalError) and "no such column" in str(e).lower():
            logger.info("Column migration not needed")
        else:
            logger.error(f"DB migration error: {e}")
    conn.commit()

def insert_metric(cpu_percent: float, memory_percent: float, disk_used_gb: float, disk_free_gb: float, disk_total_gb: float = None, disk_percent: float = None):
    disk_total_gb = max(0.0, disk_total_gb) if disk_total_gb is not None else 0.0
    disk_percent = max(0.0, min(100.0, disk_percent)) if disk_percent is not None else 0.0
    conn = get_connection()
    cursor = conn.cursor()
    cursor.execute('''
        INSERT INTO metrics (timestamp, cpu_percent, memory_percent, disk_used_gb, disk_free_gb, disk_total_gb, disk_percent)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    ''', (int(time.time() * 1000), cpu_percent, memory_percent, disk_used_gb, disk_free_gb, disk_total_gb, disk_percent))
    conn.commit()

async def insert_metric_async(**kwargs):
    """Async wrapper for insert_metric using thread pool — N97: offload blocking sqlite I/O."""
    return await asyncio.to_thread(insert_metric, **kwargs)

def get_history(hours: int = 24) -> list[dict]:
    conn = get_connection()
    cursor = conn.cursor()
    time_limit_ms = int((time.time() - (hours * 3600)) * 1000)
    cursor.execute('''
        SELECT timestamp, cpu_percent, memory_percent, disk_used_gb, disk_free_gb, disk_total_gb, disk_percent
        FROM metrics
        WHERE timestamp >= ?
        ORDER BY timestamp ASC
    ''', (time_limit_ms,))
    rows = cursor.fetchall()
    return [dict(row) for row in rows]

def cleanup_old_metrics(days: int = 30):
    conn = get_connection()
    cursor = conn.cursor()
    time_limit_ms = int((time.time() - (days * 24 * 3600)) * 1000)
    cursor.execute('DELETE FROM metrics WHERE timestamp < ?', (time_limit_ms,))
    conn.commit()

def shutdown_db():
    close_connection()
