import asyncio
import logging
import os
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from fastapi import APIRouter, Header, HTTPException, Request
from slowapi import Limiter
from slowapi.util import get_remote_address
import docker
from docker.errors import DockerException, NotFound

from auth import check_auth, ROLE_ADMIN


def validate_container_id(container_id: str) -> bool:
    """Validate container ID format (12-64 hex characters)"""
    if not container_id or len(container_id) < 12:
        return False
    return bool(re.match(r'^[a-fA-F0-9]{12,64}$', container_id))


logger = logging.getLogger("tgmon")

router = APIRouter(prefix='/docker', tags=['docker'])
limiter = Limiter(key_func=get_remote_address)

# ---------------------------------------------------------------------------
# Security: Docker socket hardening
# ---------------------------------------------------------------------------
# Read-only endpoints (no state change, no audit required beyond access log):
#   GET /docker/containers, GET /docker/containers/{id}/logs,
#   GET /docker/containers/{id}/details, GET /docker/service-urls
# Write endpoints (mutating, require audit log with container_id + remote IP):
#   POST /docker/containers/{id}/restart, POST .../stop, POST .../start,
#   DELETE .../{id}, POST .../pause, POST .../unpause
# All container_id inputs are validated via validate_container_id() regex
# ^[a-fA-F0-9]{12,64}$ — only hex chars, no shell metachars → no command
# injection via docker SDK (client.containers.get uses Docker API, not shell).
# ---------------------------------------------------------------------------

def _audit_log(request: Request, operation: str, container_id: str):
    """Audit log for mutating docker ops: container_id + remote IP."""
    try:
        remote_ip = get_remote_address(request)
    except Exception:
        try:
            remote_ip = request.client.host if getattr(request, "client", None) else "unknown"
        except Exception:
            remote_ip = "unknown"
    # container_id already validated hex; safe to log, no token/secret
    logger.info(f"AUDIT docker {operation} container_id={container_id} remote_ip={remote_ip}")


# Secret redaction for logs endpoint — prevent leaking env secrets in output
_SENSITIVE_LOG_PATTERN = re.compile(
    r'(?i)(password|passwd|secret|token|api[_-]?key|credential|auth)\s*[:=]\s*[^\s\'"`,;]+'
)
_BEARER_PATTERN = re.compile(r'(?i)bearer\s+[A-Za-z0-9_\-\.]{20,}')

def _redact_secrets(text: str) -> str:
    """Redact sensitive key=value and bearer tokens from container logs."""
    try:
        text = _SENSITIVE_LOG_PATTERN.sub(r'\1=***REDACTED***', text)
        text = _BEARER_PATTERN.sub('Bearer ***REDACTED***', text)
        return text
    except Exception:
        return text
try:
    client = docker.from_env()
except Exception as e:
    logger.error(f"Failed to initialize Docker client: {e}")
    client = None

cached_containers = []
_cache_timestamp = 0
_cache_lock = threading.Lock()
_cache_thread = None
_cache_running = False
# N97 low-resource: capped at 4 workers to limit CPU/memory while still parallelizing
# container_stats_sync (each does blocking Docker SDK + psutil). Keeps N97 CPU from thrashing.
_docker_executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="docker-stats")

def update_docker_cache():
    global cached_containers, _cache_running, _cache_timestamp
    while _cache_running:
        try:
            if client:
                containers = client.containers.list(all=True)
                futures = list(_docker_executor.map(container_stats_sync, containers))
                with _cache_lock:
                    cached_containers = futures
                    _cache_timestamp = time.time()
        except Exception as e:
            logger.error(f"Error updating docker cache: {e}")
        # N97: blocking sleep is OK here — this runs in dedicated daemon thread, not event loop;
        # 5s interval balances freshness vs CPU on low-resource device.
        time.sleep(5)

def start_docker_cache():
    global _cache_thread, _cache_running
    if _cache_thread is None or not _cache_thread.is_alive():
        _cache_running = True
        _cache_thread = threading.Thread(target=update_docker_cache, daemon=True)
        _cache_thread.start()
        logger.info("Docker cache thread started")

def stop_docker_cache():
    global _cache_running
    _cache_running = False
    if _cache_thread and _cache_thread.is_alive():
        _cache_thread.join(timeout=5)
        logger.info("Docker cache thread stopped")

def shutdown_docker_executor():
    _docker_executor.shutdown(wait=False)

def get_docker_summary():
    # N97/low-resource: O(n) single pass counts, no hardcoded totals (e.g., no 84).
    # total = actual cached list length, running/restarting filtered by status,
    # stopped = total - running - restarting (includes paused/exited/created; paused counted as stopped by design).
    # This keeps counts accurate even as containers are added/removed; avoid static numbers.
    with _cache_lock:
        total = len(cached_containers)
        running = sum(1 for c in cached_containers if c['status'] == 'running')
        restarting = sum(1 for c in cached_containers if c['status'] == 'restarting')
        stopped = total - running - restarting
    return {
        "total": total,
        "running": running,
        "restarting": restarting,
        "stopped": stopped
    }

def parse_started_at(started_at: str) -> int:
    s = started_at.rstrip('Z')
    if '.' in s:
        base, frac = s.split('.', 1)
        frac = ''.join(ch for ch in frac if ch.isdigit())[:6]
        s = f"{base}.{frac}"
    try:
        dt = datetime.fromisoformat(s).replace(tzinfo=timezone.utc)
        return int(time.time() - dt.timestamp())
    except Exception:
        return 0

def format_uptime(seconds: int) -> str:
    h = seconds // 3600
    m = (seconds % 3600) // 60
    s = seconds % 60
    return f"{h:02d}:{m:02d}:{s:02d}"

def container_stats_sync(c):
    cpu_percent = 0.0
    mem_mb = 0.0
    mem_percent = 0.0
    network_rx_mb = 0.0
    network_tx_mb = 0.0

    try:
        if c.status == 'running':
            stats = c.stats(stream=False)
            cpu_delta = (
                stats['cpu_stats']['cpu_usage']['total_usage']
                - stats['precpu_stats']['cpu_usage']['total_usage']
            )
            system_delta = (
                stats['cpu_stats'].get('system_cpu_usage', 0)
                - stats['precpu_stats'].get('system_cpu_usage', 0)
            )
            online_cpus = (
                stats.get('cpu_stats', {}).get('online_cpus')
                or len(stats.get('cpu_stats', {}).get('cpu_usage', {}).get('percpu_usage', []))
                or 1
            )
            if system_delta > 0:
                cpu_percent = (cpu_delta / system_delta) * online_cpus * 100.0

            mem_usage = stats['memory_stats'].get('usage', 0) / (1024 * 1024)
            mem_limit = float(stats['memory_stats'].get('limit', 0) or 0) / (1024 * 1024)
            mem_mb = mem_usage
            if mem_limit > 0:
                mem_percent = (mem_usage / mem_limit) * 100.0

            network_rx_mb = 0.0
            network_tx_mb = 0.0
            networks = stats.get('networks', {})
            for iface, net_data in networks.items():
                network_rx_mb += net_data.get('rx_bytes', 0)
                network_tx_mb += net_data.get('tx_bytes', 0)
            network_rx_mb = network_rx_mb / (1024 * 1024)
            network_tx_mb = network_tx_mb / (1024 * 1024)
    except Exception as e:
        logger.warning(f"Failed to get stats for container {c.name}: {e}")
        cpu_percent = 0.0
        mem_mb = 0.0
        mem_percent = 0.0
        network_rx_mb = 0.0
        network_tx_mb = 0.0

    health_status = c.attrs.get('State', {}).get('Health', {}).get('Status', 'none')

    started_at = c.attrs.get('State', {}).get('StartedAt', '')
    uptime_seconds = parse_started_at(started_at) if started_at and c.status == 'running' else 0

    image = 'unknown'
    try:
        image = c.image.tags[0] if c.image.tags else 'unknown'
    except Exception:
        pass

    # Get ports
    ports = []
    try:
        container_ports = c.ports  # This is a dict like {'80/tcp': [{'HostPort': '8080', ...}]}
        for container_port_key, bindings in container_ports.items():
            if bindings and len(bindings) > 0:
                binding = bindings[0]
                host_port = binding.get('HostPort')
                if host_port:
                    try:
                        port_num = int(host_port)
                        if port_num < 1 or port_num > 65535:
                            continue
                    except ValueError:
                        continue
                    parts = container_port_key.split('/')
                    container_port = int(parts[0]) if parts else 0
                    protocol = parts[1] if len(parts) > 1 else 'tcp'
                    ports.append({
                        'host_port': port_num,
                        'container_port': container_port,
                        'protocol': protocol
                    })
    except Exception as e:
        logger.warning(f"Failed to get ports for {c.name}: {e}")

    return {
      "id": c.id,
      "name": c.name,
      "status": c.status,
      "image": image,
      "cpu_percent": round(cpu_percent, 2),
      "memory_mb": round(mem_mb, 2),
      "memory_percent": round(mem_percent, 2),
      "uptime_seconds": uptime_seconds,
      "uptime_hms": format_uptime(uptime_seconds),
      "health_status": health_status,
      "network_rx_mb": round(network_rx_mb, 2),
      "network_tx_mb": round(network_tx_mb, 2),
      "ports": ports
    }

container_stats = container_stats_sync

async def get_container_stats_async(c):
    return await asyncio.get_event_loop().run_in_executor(
        _docker_executor, container_stats_sync, c
    )

@router.get('/containers')
@limiter.limit("20/minute")
async def list_containers(request: Request, authorization: str = Header(None)):
    check_auth(authorization)
    if not client:
        raise HTTPException(status_code=500, detail="Docker client unavailable")
    try:
        def _get_containers():
            with _cache_lock:
                return list(cached_containers), _cache_timestamp
        result, ts = await asyncio.wait_for(asyncio.to_thread(_get_containers), timeout=30.0)
        return {
            "containers": result,
            "cache_timestamp": ts,
            "cache_age_seconds": int(time.time() - ts) if ts else None
        }
    except asyncio.TimeoutError:
        logger.error("Container list timeout")
        raise HTTPException(status_code=504, detail="Container list request timeout")
    except Exception as e:
        logger.error(f"List containers error: {e}")
        raise HTTPException(status_code=500, detail="Failed to list containers")

@router.post('/containers/{container_id}/restart')
@limiter.limit("20/minute")
def restart_container(request: Request, container_id: str, authorization: str = Header(None)):
    check_auth(authorization, required_role=ROLE_ADMIN)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    _audit_log(request, "restart", container_id)
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        if c.status == 'restarting':
            raise HTTPException(status_code=400, detail='Container is already restarting')
        if c.status != 'running':
            raise HTTPException(status_code=400, detail='Container is not running, cannot restart')
        c.restart()
        _broadcast_container_event(container_id, c.name, 'restarting')
        logger.info(f"AUDIT success restart container_id={container_id} name={c.name}")
        return {'success': True, 'message': 'Container restarted', 'container_id': container_id}
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except HTTPException:
        raise
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

@router.get('/containers/{container_id}/logs')
@limiter.limit("20/minute")
async def container_logs(request: Request, container_id: str, lines: int = 50, authorization: str = Header(None)):
    check_auth(authorization)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    if lines < 1 or lines > 10000:
        raise HTTPException(status_code=400, detail="Lines must be between 1 and 10000")
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        
        def _fetch_logs():
            c = client.containers.get(container_id)
            tail = min(lines, 500)
            logs = c.logs(tail=tail).decode('utf-8', errors='ignore')
            # Redact secrets before truncating to avoid leaking env values in logs
            logs = _redact_secrets(logs)
            return logs[:50000]
        
        logs = await asyncio.wait_for(asyncio.to_thread(_fetch_logs), timeout=120.0)
        return {'container_id': container_id, 'logs': logs}
    except asyncio.TimeoutError:
        logger.error(f"Logs fetch timeout for container {container_id}")
        raise HTTPException(status_code=504, detail="Logs request timeout")
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")
    except Exception as e:
        logger.error(f"Container logs error: {e}")
        raise HTTPException(status_code=500, detail="Failed to fetch logs")

@router.post('/containers/{container_id}/stop')
@limiter.limit("20/minute")
def stop_container(request: Request, container_id: str, authorization: str = Header(None)):
    check_auth(authorization, required_role=ROLE_ADMIN)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    _audit_log(request, "stop", container_id)
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        c.stop()
        _broadcast_container_event(container_id, c.name, 'exited')
        logger.info(f"AUDIT success stop container_id={container_id} name={c.name}")
        return {'success': True, 'message': 'Container stopped', 'container_id': container_id}
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

@router.post('/containers/{container_id}/start')
@limiter.limit("20/minute")
def start_container(request: Request, container_id: str, authorization: str = Header(None)):
    check_auth(authorization, required_role=ROLE_ADMIN)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    _audit_log(request, "start", container_id)
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        c.start()
        _broadcast_container_event(container_id, c.name, 'running')
        logger.info(f"AUDIT success start container_id={container_id} name={c.name}")
        return {'success': True, 'message': 'Container started', 'container_id': container_id}
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

@router.delete('/containers/{container_id}')
@limiter.limit("20/minute")
def remove_container(request: Request, container_id: str, force: bool = False, authorization: str = Header(None)):
    check_auth(authorization, required_role=ROLE_ADMIN)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    _audit_log(request, f"remove force={force}", container_id)
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        if c.status == 'running' and not force:
            raise HTTPException(status_code=400, detail='Cannot remove a running container without force flag')
        cname = c.name
        c.remove(force=force)
        logger.info(f"AUDIT success remove container_id={container_id} name={cname} force={force}")
        return {'success': True, 'message': 'Container removed'}
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

@router.post('/containers/{container_id}/pause')
@limiter.limit("20/minute")
def pause_container_endpoint(request: Request, container_id: str, authorization: str = Header(None)):
    check_auth(authorization, required_role=ROLE_ADMIN)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    _audit_log(request, "pause", container_id)
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        if c.status != 'running':
            raise HTTPException(status_code=400, detail='Container is not running, cannot pause')
        c.pause()
        _broadcast_container_event(container_id, c.name, 'paused')
        logger.info(f"AUDIT success pause container_id={container_id} name={c.name}")
        return {'success': True, 'message': 'Container paused', 'container_id': container_id}
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except HTTPException:
        raise
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

@router.post('/containers/{container_id}/unpause')
@limiter.limit("20/minute")
def unpause_container_endpoint(request: Request, container_id: str, authorization: str = Header(None)):
    check_auth(authorization, required_role=ROLE_ADMIN)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    _audit_log(request, "unpause", container_id)
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        if c.status != 'paused':
            raise HTTPException(status_code=400, detail='Container is not paused, cannot unpause')
        c.unpause()
        _broadcast_container_event(container_id, c.name, 'running')
        logger.info(f"AUDIT success unpause container_id={container_id} name={c.name}")
        return {'success': True, 'message': 'Container unpaused', 'container_id': container_id}
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except HTTPException:
        raise
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

@router.get('/service-urls')
@limiter.limit("20/minute")
def service_urls(request: Request, authorization: str = Header(None)):
    check_auth(authorization)
    return {'service_urls': parse_docker_compose()}

@router.get('/containers/{container_id}/details')
@limiter.limit("20/minute")
def container_details(request: Request, container_id: str, authorization: str = Header(None)):
    # N97: kept sync (not async) because FastAPI runs this in threadpool; heavy stats
    # go through container_stats_sync which already uses blocking Docker SDK. No extra
    # asyncio.to_thread needed here — would add overhead. If migrated to full async,
    # use await asyncio.to_thread(container_stats_sync, c) + make endpoint async.
    check_auth(authorization)
    if not validate_container_id(container_id):
        raise HTTPException(status_code=400, detail="Invalid container ID format")
    try:
        if not client:
            raise HTTPException(status_code=500, detail="Docker client unavailable")
        c = client.containers.get(container_id)
        
        stats_data = container_stats(c)
        
        ports = []
        try:
            ports_config = c.attrs.get('NetworkSettings', {}).get('Ports', {})
            for container_port, host_bindings in ports_config.items():
                if host_bindings:
                    for binding in host_bindings:
                        host_port = binding.get('HostPort', '')
                        protocol = container_port.split('/')[-1] if '/' in container_port else 'tcp'
                        container_port_num = container_port.split('/')[0] if '/' in container_port else container_port
                        try:
                            container_port_int = int(container_port_num)
                        except ValueError:
                            container_port_int = 0
                        try:
                            host_port_int = int(host_port)
                            if host_port_int < 1 or host_port_int > 65535:
                                host_port_int = 0
                        except ValueError:
                            host_port_int = 0
                        if host_port_int > 0 and container_port_int > 0:
                            ports.append({
                                'host_port': host_port_int,
                                'container_port': container_port_int,
                                'protocol': protocol,
                                'type': 'http'
                            })
        except Exception:
            pass
        
        networks = []
        try:
            networks_data = c.attrs.get('NetworkSettings', {}).get('Networks', {})
            networks = list(networks_data.keys())
        except Exception:
            pass
        
        volumes = []
        try:
            binds = c.attrs.get('HostConfig', {}).get('Binds', []) or []
            volumes = list(binds)
        except Exception:
            pass
        
        sensitive_patterns = ('PASSWORD', 'PASSWD', 'SECRET', 'KEY', 'TOKEN', 'CREDENTIAL', 'AUTH', 'PRIVATE', 'CERT')
        env_vars = {}
        try:
            env_list = c.attrs.get('Config', {}).get('Env', []) or []
            for env in env_list:
                if '=' in env:
                    key, value = env.split('=', 1)
                    if key.startswith('_'):
                        continue
                    if any(p in key.upper() for p in sensitive_patterns):
                        env_vars[key] = "***REDACTED***"
                    else:
                        if '://' in value and '@' in value:
                            value = re.sub(r'://([^:]+):([^@]+)@', r'://\1:***REDACTED***@', value)
                        env_vars[key] = value
        except Exception:
            pass
        
        restart_count = 0
        try:
            restart_count = c.attrs.get('RestartCount', 0)
        except Exception:
            pass
        
        created = ''
        try:
            created = c.attrs.get('Created', '')
        except Exception:
            pass
        
        return {
            'id': c.short_id,
            'name': c.name,
            'image': stats_data.get('image', 'unknown'),
            'status': c.status,
            'health_status': stats_data.get('health_status', 'none'),
            'created': created,
            'restart_count': restart_count,
            'uptime_seconds': stats_data.get('uptime_seconds', 0),
            'uptime_hms': stats_data.get('uptime_hms', ''),
            'ports': ports,
            'networks': networks,
            'volumes': volumes,
            'env_vars': env_vars,
            'cpu_percent': stats_data.get('cpu_percent', 0.0),
            'memory_mb': stats_data.get('memory_mb', 0.0),
            'memory_percent': stats_data.get('memory_percent', 0.0),
            'network_rx_mb': stats_data.get('network_rx_mb', 0.0),
            'network_tx_mb': stats_data.get('network_tx_mb', 0.0)
        }
    except NotFound:
        raise HTTPException(status_code=404, detail='Container not found')
    except DockerException:
        raise HTTPException(status_code=500, detail="Docker operation failed")

def parse_docker_compose():
    default_path = os.environ.get('COMPOSE_DEFAULT_PATH', './docker-compose.yml')
    compose_path = os.environ.get('COMPOSE_PATH', default_path)
    if not compose_path or not (compose_path.endswith('.yml') or compose_path.endswith('.yaml')):
        return []
    if not os.path.isfile(compose_path):
        return []
    try:
        import yaml
        with open(compose_path, 'r') as f:
            compose_data = yaml.safe_load(f)
        if not compose_data or 'services' not in compose_data:
            return []
        webui_port = os.environ.get('WEBUI_PORT')
        skip_ports = {22, 3306, 5432, 6379, 27017, 6881, 61208, 9500, 9001, 8125}
        services = []
        for name, config in compose_data['services'].items():
            ports = config.get('ports', [])
            if not ports:
                continue
            public_port = None
            for port_mapping in ports:
                port_str = str(port_mapping)
                if ':' in port_str:
                    try:
                        port_part = port_str.split(':')[-1].strip()
                        if '/' in port_part:
                            port_part = port_part.split('/')[0]
                        port_num = int(port_part)
                    except (ValueError, IndexError):
                        continue
                else:
                    try:
                        port_num = int(port_str.split('/')[0].strip())
                    except (ValueError, IndexError):
                        continue
                if port_num not in skip_ports:
                    public_port = port_num
                    break
            if public_port is None:
                continue
            if webui_port and str(public_port) == webui_port:
                continue
            protocol = 'https' if public_port in (443, 8443) else 'http'
            services.append({
                'container': name,
                'public_port': public_port,
                'protocol': protocol
            })
        return services
    except Exception as e:
        logger.error(f"Failed to parse docker-compose: {e}")
        return []

def _broadcast_container_event(container_id: str, container_name: str, new_status: str):
    import json
    event = json.dumps({
        'type': 'container_event',
        'payload': {
            'container_id': container_id,
            'container_name': container_name,
            'new_status': new_status
        }
    })
    broadcast_ws(event)

_broadcast_queue: asyncio.Queue | None = None
_MAX_BROADCAST_QUEUE_SIZE = 100
_loop = None

def init_docker_api_loop(loop):
    global _broadcast_queue, _loop
    _loop = loop
    _broadcast_queue = asyncio.Queue(maxsize=_MAX_BROADCAST_QUEUE_SIZE)

def broadcast_ws(message: str):
    try:
        if _loop and _broadcast_queue:
            _loop.call_soon_threadsafe(_try_put_message, message)
    except Exception:
        pass

def _try_put_message(message: str):
    """Try to put message in queue, discard oldest if full."""
    try:
        _broadcast_queue.put_nowait(message)
    except asyncio.QueueFull:
        try:
            _broadcast_queue.get_nowait()
            _broadcast_queue.put_nowait(message)
        except (asyncio.QueueEmpty, asyncio.QueueFull):
            pass

async def _broadcast_worker():
    from main import manager
    while True:
        try:
            if _broadcast_queue:
                message = await asyncio.wait_for(
                    _broadcast_queue.get(),
                    timeout=5.0
                )
                await manager.broadcast(message)
        except asyncio.TimeoutError:
            continue
        except Exception as e:
            logger.error(f"Broadcast worker error: {e}")
        await asyncio.sleep(0.01)

def cleanup_broadcast_queue():
    """Clear and cleanup the broadcast queue."""
    global _broadcast_queue
    if _broadcast_queue:
        while not _broadcast_queue.empty():
            try:
                _broadcast_queue.get_nowait()
            except asyncio.QueueEmpty:
                break
        _broadcast_queue = None
