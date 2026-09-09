import os
from dotenv import load_dotenv

# Load env vars BEFORE any other imports that depend on them
load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env.api"))

import asyncio
import json
import logging
import secrets
import socket
import time
import uuid
import psutil

from contextlib import asynccontextmanager
from fastapi import FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, PlainTextResponse
from slowapi import Limiter
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded
from docker_api import router as docker_router, get_docker_summary, start_docker_cache, stop_docker_cache, client, _broadcast_worker, init_docker_api_loop
from db import init_db, insert_metric, get_history, cleanup_old_metrics, shutdown_db, get_connection
from auth import check_auth, TOKEN, invalidate_token

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("tgmon")

SERVER_VERSION = "2.0.0"
API_VERSION_HEADER = "X-API-Version"

_cpu_initialized = False
_event_debounce: dict[str, float] = {}

def _process_docker_events(last_status: dict):
    """Process Docker events (runs in executor)."""
    from docker_api import broadcast_ws
    try:
        for event in client.events(filters={'type': ['container']}, decode=True):
            action = event.get('Action', '')
            if action not in ('start', 'die', 'restart', 'pause', 'unpause'):
                continue
            actor = event.get('Actor', {})
            container_id = actor.get('ID', '')
            attributes = actor.get('Attributes', {})
            container_name = attributes.get('name', container_id[:12])
            new_status = 'running' if action in ('start', 'unpause') else ('paused' if action == 'pause' else 'stopped')
            last_status[container_id] = new_status
            now = time.time()
            last_time = _event_debounce.get(container_id, 0)
            if now - last_time < 30:
                continue
            _event_debounce[container_id] = now
            broadcast_ws(json.dumps({
                "type": "container_event",
                "payload": {
                    "container_id": container_id[:12],
                    "container_name": container_name,
                    "new_status": new_status
                }
            }))
    except Exception as e:
        logger.error(f"Docker events error: {e}")

async def docker_events_listener_async():
    """Async version of docker events listener."""
    last_status: dict[str, str] = {}
    while True:
        try:
            if client:
                loop = asyncio.get_running_loop()
                await loop.run_in_executor(None, _process_docker_events, last_status)
            await asyncio.sleep(1)
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error(f"Events listener error: {e}")
            await asyncio.sleep(5)
    logger.info("Docker events listener stopped")

def get_disks_data() -> list[dict]:
    """Discover and return stats for all mounted physical storage drives."""
    disks = []
    seen = set()
    candidate_mounts = []

    # 1. Standard partitions (all=False)
    try:
        parts = psutil.disk_partitions(all=False)
        for p in parts:
            candidate_mounts.append((p.device, p.mountpoint, p.fstype))
    except Exception:
        pass

    # 2. Linux & Docker supplement: all=True allows fuseblk (NTFS/exFAT external drives on Linux),
    # network mounts (cifs/nfs), and mounted container volumes without being dropped by all=False.
    IGNORED_FSTYPES = {
        "tmpfs", "devtmpfs", "squashfs", "proc", "sysfs", "cgroup", "cgroup2",
        "nsfs", "devpts", "pstore", "securityfs", "selinuxfs", "mqueue",
        "hugetlbfs", "debugfs", "tracefs", "fusectl", "configfs", "ramfs",
        "autofs", "binfmt_misc"
    }

    try:
        for p in psutil.disk_partitions(all=True):
            if p.fstype.lower() in IGNORED_FSTYPES:
                continue
            if "/docker/" in p.mountpoint or "/containerd/" in p.mountpoint:
                continue
            candidate_mounts.append((p.device, p.mountpoint, p.fstype))
    except Exception:
        pass

    # 3. Auto-discover mounted external/secondary drives in common Linux & container locations
    auto_locations = [
        "/mnt", "/media", "/run/media", "/disks", "/storage", "/data",
        "/volume1", "/volume2", "/host/mnt", "/host/media"
    ]
    for base in auto_locations:
        if os.path.isdir(base):
            try:
                for entry in os.scandir(base):
                    if entry.is_dir():
                        candidate_mounts.append((entry.path, entry.path, "auto-mount"))
            except Exception:
                pass

    # 4. Explicit user override via TGMON_DISKS env var (e.g. TGMON_DISKS=/,/mnt/hdd)
    explicit_disks = os.getenv("TGMON_DISKS", "").strip()
    explicit_paths = [p.strip() for p in explicit_disks.split(",") if p.strip()] if explicit_disks else []
    for ep in explicit_paths:
        candidate_mounts.append((ep, ep, "custom"))

    # Ensure system root drive is present
    root_path = os.getenv("SystemDrive", "C:") + "\\" if os.name == 'nt' else "/"
    if not any(m[1].rstrip("\\/").upper() == root_path.rstrip("\\/").upper() for m in candidate_mounts):
        candidate_mounts.insert(0, (root_path, root_path, ""))

    seen_signatures = set()
    for device, mount, fstype in candidate_mounts:
        norm_key = mount.rstrip("\\/").upper() if os.name == 'nt' else mount.rstrip("/")
        if not norm_key or norm_key in seen:
            continue
        try:
            u = psutil.disk_usage(mount)
            if u.total == 0:
                continue
            # Deduplicate drives that map to the same underlying filesystem
            sig = (round(u.total / 1024**3, 1), round(u.used / 1024**3, 1))
            if sig in seen_signatures and norm_key != root_path.rstrip("\\/").upper() and norm_key != "/":
                continue
            seen.add(norm_key)
            seen_signatures.add(sig)
            disks.append({
                "device": device or mount,
                "mountpoint": mount,
                "fstype": fstype,
                "total_gb": round(u.total / 1024**3, 2),
                "used_gb": round(u.used / 1024**3, 2),
                "free_gb": round(u.free / 1024**3, 2),
                "percent": round(u.percent, 1)
            })
        except Exception:
            pass

    return disks

def get_health_data() -> dict:
    global _cpu_initialized

    disks = get_disks_data()
    primary_disk = disks[0] if disks else None
    disk_used_gb = primary_disk["used_gb"] if primary_disk else 0.0
    disk_free_gb = primary_disk["free_gb"] if primary_disk else 0.0
    disk_total_gb = primary_disk["total_gb"] if primary_disk else 0.0
    disk_percent = primary_disk["percent"] if primary_disk else 0.0

    memory = psutil.virtual_memory().percent

    if not _cpu_initialized:
        cpu = psutil.cpu_percent(interval=0.1)
        _cpu_initialized = True
    else:
        cpu = psutil.cpu_percent(interval=None)

    try:
        net_io = psutil.net_io_counters()
        net_recv_mb = round(net_io.bytes_recv / 1024**2, 1)
        net_sent_mb = round(net_io.bytes_sent / 1024**2, 1)
    except Exception:
        net_recv_mb = 0.0
        net_sent_mb = 0.0

    try:
        disk_io = psutil.disk_io_counters()
        disk_read_mb  = round(disk_io.read_bytes  / 1024**2, 1) if disk_io else 0.0
        disk_write_mb = round(disk_io.write_bytes / 1024**2, 1) if disk_io else 0.0
    except Exception:
        disk_read_mb  = 0.0
        disk_write_mb = 0.0

    try:
        docker_data = get_docker_summary()
    except Exception:
        docker_data = {"total": 0, "running": 0, "restarting": 0, "stopped": 0}

    # Hostname resolution:
    # 1. HOSTNAME_OVERRIDE env var (e.g. HOSTNAME_OVERRIDE=shodan)
    # 2. Mounted host hostname files (common Docker pattern: -v /etc/hostname:/etc/host_hostname:ro)
    # 3. SERVER_NAME or DEFAULT_SERVER_NAME env var
    # 4. HOSTNAME env var or socket.gethostname()
    hostname = os.getenv("HOSTNAME_OVERRIDE")
    if not hostname:
        for host_file in ["/etc/host_hostname", "/host/etc/hostname"]:
            if os.path.exists(host_file):
                try:
                    with open(host_file, "r") as f:
                        h = f.read().strip()
                        if h:
                            hostname = h
                            break
                except Exception:
                    pass
    if not hostname:
        _raw_hostname = os.getenv("SERVER_NAME") or os.getenv("HOSTNAME") or socket.gethostname()
        if isinstance(_raw_hostname, bytes):
            hostname = _raw_hostname.decode("utf-8", errors="ignore")
        else:
            hostname = str(_raw_hostname)
    # Fallback to "unknown" if still blank after stripping
    if not hostname or not hostname.strip():
        hostname = "unknown"

    return {
        "hostname": hostname,
        "cpu_percent": cpu,
        "memory_percent": memory,
        "disk_used_gb": disk_used_gb,
        "disk_free_gb": disk_free_gb,
        "disk_total_gb": disk_total_gb,
        "disk_percent": disk_percent,
        "disks": disks,
        "net_recv_mb": net_recv_mb,
        "net_sent_mb": net_sent_mb,
        "disk_read_mb": disk_read_mb,
        "disk_write_mb": disk_write_mb,
        "docker": docker_data,
        "uptime_seconds": int(time.time() - psutil.boot_time()),
        "load_avg": list(os.getloadavg()) if hasattr(os, 'getloadavg') else None,
        "memory_total_gb": round(psutil.virtual_memory().total / (1024**3), 1)
    }

async def save_metrics_to_db() -> None:
    while True:
        try:
            health_data = get_health_data()
            disk_used = health_data["disk_used_gb"]
            disk_total = health_data["disk_total_gb"]
            disk_percent = (disk_used / disk_total) * 100 if disk_total > 0 else 0

            await asyncio.to_thread(
                insert_metric,
                cpu_percent=health_data["cpu_percent"],
                memory_percent=health_data["memory_percent"],
                disk_used_gb=disk_used,
                disk_free_gb=health_data["disk_free_gb"],
                disk_total_gb=disk_total,
                disk_percent=disk_percent
            )
        except Exception as e:
            logger.error(f"Error saving to DB: {e}")
        await asyncio.sleep(300)

async def maintenance_task() -> None:
    def _do_maintenance():
        cleanup_old_metrics(days=30)
        conn = get_connection()
        cursor = conn.cursor()
        cursor.execute('SELECT COUNT(*) FROM metrics')
        count = cursor.fetchone()[0]
        cursor.execute('SELECT MIN(timestamp), MAX(timestamp) FROM metrics')
        row = cursor.fetchone()
        logger.info(f"DB: {count} metrics after cleanup, range: {row[0]} - {row[1]}")

    while True:
        try:
            await asyncio.to_thread(_do_maintenance)
        except Exception as e:
            logger.error(f"Maintenance Error: {e}")
        await asyncio.sleep(86400)

@asynccontextmanager
async def lifespan(app: FastAPI):
    global _cpu_initialized, SERVER_START_TIME
    init_db()
    SERVER_START_TIME = time.time()
    psutil.cpu_percent(interval=None)
    _cpu_initialized = False
    start_docker_cache()
    init_docker_api_loop(asyncio.get_running_loop())

    tasks = [
        asyncio.create_task(save_metrics_to_db()),
        asyncio.create_task(ws_broadcaster()),
        asyncio.create_task(maintenance_task()),
        asyncio.create_task(_broadcast_worker()),
        asyncio.create_task(docker_events_listener_async()),
    ]

    yield

    for task in tasks:
        task.cancel()
    await asyncio.gather(*tasks, return_exceptions=True)
    await asyncio.to_thread(stop_docker_cache)
    shutdown_db()
    logger.info("Server shutting down")

app = FastAPI(
    title="tgmon-api",
    docs_url=None,
    redoc_url=None,
    lifespan=lifespan
)

def get_cors_origins() -> list[str]:
    """Get CORS origins from environment, with secure defaults."""
    env_origins = os.getenv("CORS_ORIGINS", "")
    if env_origins:
        return [origin.strip() for origin in env_origins.split(",") if origin.strip()]
    # Secure defaults - localhost only in development
    return ["http://localhost:3000", "http://127.0.0.1:3000"]

app.add_middleware(
    CORSMiddleware,
    allow_origins=get_cors_origins(),
    allow_credentials=False,  # Cannot use True with wildcard origins - security risk
    allow_methods=["*"],
    allow_headers=["*"],
)

limiter = Limiter(key_func=get_remote_address)
app.state.limiter = limiter

@app.middleware("http")
async def add_version_header(request: Request, call_next):
    response = await call_next(request)
    response.headers[API_VERSION_HEADER] = SERVER_VERSION
    return response

@app.middleware("http")
async def handle_invalid_json(request: Request, call_next):
    try:
        return await call_next(request)
    except json.JSONDecodeError as e:
        logger.error(f"Invalid JSON in request: {e}")
        return JSONResponse(
            status_code=400,
            content={"detail": "Invalid JSON in request body"}
        )
    except Exception as e:
        logger.error(f"Request error: {e}")
        return JSONResponse(
            status_code=500,
            content={"detail": "Internal server error"}
        )

@app.exception_handler(RateLimitExceeded)
async def rate_limit_handler(request: Request, exc: RateLimitExceeded) -> JSONResponse:
    return JSONResponse(
        status_code=429,
        content={"detail": "Rate limit exceeded"}
    )

app.include_router(docker_router)

@app.get("/health")
@limiter.limit("30/minute")
def health(request: Request, authorization: str = Header(None)) -> dict:
    try:
        check_auth(authorization)
        return get_health_data()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Health check error: {e}")
        raise HTTPException(status_code=503, detail="Service unavailable")

@app.get("/history")
@limiter.limit("10/minute")
def history(request: Request, hours: int = 24, authorization: str = Header(None)) -> list[dict]:
    check_auth(authorization)
    if hours < 1 or hours > 8760:
        raise HTTPException(status_code=400, detail="Hours must be between 1 and 8760")
    try:
        return get_history(hours)
    except Exception as e:
        logger.error(f"History query error: {e}")
        raise HTTPException(status_code=503, detail="History service temporarily unavailable")

@app.get("/history/csv")
@limiter.limit("10/minute")
def history_csv_export(request: Request, hours: int = 24, authorization: str = Header(None)) -> PlainTextResponse:
    check_auth(authorization)
    if hours < 1 or hours > 8760:
        raise HTTPException(status_code=400, detail="Hours must be between 1 and 8760")
    try:
        records = get_history(hours)
    except Exception as e:
        logger.error(f"History CSV export error: {e}")
        raise HTTPException(status_code=503, detail="History service temporarily unavailable")
    lines = ["timestamp,cpu_percent,memory_percent,disk_used_gb,disk_free_gb,disk_total_gb,disk_percent"]
    for r in records:
        lines.append(f"{r['timestamp']},{r['cpu_percent']},{r['memory_percent']},{r['disk_used_gb']},{r['disk_free_gb']},{r['disk_total_gb']},{r.get('disk_percent', '')}")
    return PlainTextResponse(content="\n".join(lines), media_type="text/csv")

@app.get("/status")
@limiter.limit("10/minute")
def status(request: Request, authorization: str = Header(None)) -> dict:
    check_auth(authorization)
    uptime = int(time.time() - SERVER_START_TIME)

    def is_process_running(name: str) -> bool:
        try:
            for p in psutil.process_iter(["name"]):
                if p.info.get("name") == name:
                    return True
        except Exception:
            pass
        return False

    try:
        load_avg = list(os.getloadavg())
    except (OSError, AttributeError):
        load_avg = None

    return {
        "uptime_seconds": uptime,
        "load_avg": load_avg,
        "docker_active": is_process_running("dockerd"),
        "ssh_active": is_process_running("sshd"),
    }

@app.post("/auth/invalidate")
@limiter.limit("5/minute")
def invalidate_token_endpoint(request: Request, authorization: str = Header(None)) -> dict:
    check_auth(authorization)
    invalidate_token()
    return {"success": True, "message": "Token invalidated"}

@app.get("/auth/verify")
@limiter.limit("30/minute")
def verify_token_endpoint(request: Request, authorization: str = Header(None)) -> dict:
    check_auth(authorization)
    return {"valid": True}

async def ws_broadcaster() -> None:
    while True:
        if len(manager.active_connections) > 0:
            try:
                # N97 low-CPU: get_health_data() does blocking psutil + Docker cache reads;
                # offload to thread pool so event loop stays responsive (2s broadcast interval).
                data = await asyncio.to_thread(get_health_data)
                await manager.broadcast(json.dumps({
                    "type": "telemetry",
                    "payload": data
                }))
            except Exception as e:
                logger.error(f"WS broadcast error: {e}")
        await asyncio.sleep(2)

class ConnectionManager:
    def __init__(self):
        self.active_connections: set[WebSocket] = set()

    async def connect(self, websocket: WebSocket):
        if websocket not in self.active_connections:
            self.active_connections.add(websocket)

    def disconnect(self, websocket: WebSocket):
        self.active_connections.discard(websocket)

    async def broadcast(self, message: str):
        disconnected = []
        for connection in list(self.active_connections):
            try:
                await connection.send_text(message)
            except Exception:
                disconnected.append(connection)
        for conn in disconnected:
            self.active_connections.discard(conn)

manager = ConnectionManager()

_ws_message_counts: dict[str, list[float]] = {}
_ws_rate_limit = 10

def _check_ws_rate_limit(client_id: str) -> bool:
    global _ws_message_counts
    now = time.time()
    if client_id not in _ws_message_counts:
        _ws_message_counts[client_id] = []
    _ws_message_counts[client_id] = [t for t in _ws_message_counts[client_id] if now - t < 1]
    if len(_ws_message_counts[client_id]) >= _ws_rate_limit:
        return False
    _ws_message_counts[client_id].append(now)
    return True

@app.websocket("/ws/telemetry")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    client_id = None
    try:
        auth_msg = await asyncio.wait_for(websocket.receive_json(), timeout=5)
        if auth_msg.get("type") != "auth" or not secrets.compare_digest(auth_msg.get("token", ""), TOKEN):
            await websocket.close(code=1008, reason="Unauthorized")
            return
        client_id = str(uuid.uuid4())
    except asyncio.TimeoutError:
        await websocket.close(code=1008, reason="Auth timeout")
        return
    except WebSocketDisconnect:
        return
    except Exception as e:
        logger.error(f"WS auth error: {e}")
        await websocket.close(code=1011, reason="Internal error")
        return
    await manager.connect(websocket)
    await websocket.send_text(json.dumps({"type": "auth_ok"}))
    try:
        while True:
            try:
                data = await websocket.receive_text()
            except WebSocketDisconnect:
                break
            except Exception as e:
                logger.error(f"WS receive error: {e}")
                break
            if client_id and not _check_ws_rate_limit(client_id):
                await websocket.close(code=1008, reason="Rate limit exceeded")
                break
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.error(f"WS loop error: {e}")
    finally:
        if client_id and client_id in _ws_message_counts:
            del _ws_message_counts[client_id]
        manager.disconnect(websocket)
