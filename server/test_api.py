import os
import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock
import json
import asyncio

os.environ["TGMON_TOKEN"] = "test_token_123"

from auth import TOKEN, check_auth
from main import app, manager
from db import init_db

client = TestClient(app)
AUTH_HEADER = {"Authorization": f"Bearer {TOKEN}"}

@pytest.fixture(autouse=True)
def setup_db(tmp_path):
    """Use a temporary database for each test."""
    import db
    db.DB_PATH = str(tmp_path / "test_metrics.db")
    db._local.connection = None
    init_db()
    yield
    db.close_connection()
    if os.path.exists(db.DB_PATH):
        try:
            os.remove(db.DB_PATH)
        except Exception:
            pass

@pytest.fixture(autouse=True)
def cleanup_ws():
    """Clean up WebSocket connections after each test."""
    yield
    manager.active_connections.clear()

def test_health_requires_auth():
    """Health endpoint should return 401 without auth."""
    response = client.get("/health")
    assert response.status_code == 401

def test_health_with_auth():
    """Health endpoint should return 200 with valid auth."""
    with patch("main.get_health_data") as mock_health:
        mock_health.return_value = {
            "hostname": "test",
            "cpu_percent": 50.0,
            "memory_percent": 60.0,
            "disk_used_gb": 100.0,
            "disk_free_gb": 200.0,
            "disk_total_gb": 300.0,
            "net_recv_mb": 10.0,
            "net_sent_mb": 5.0,
            "disk_read_mb": 1.0,
            "disk_write_mb": 2.0,
            "docker": {"total": 0, "running": 0, "restarting": 0, "stopped": 0}
        }
        response = client.get("/health", headers=AUTH_HEADER)
        assert response.status_code == 200
        data = response.json()
        assert data["hostname"] == "test"
        assert "cpu_percent" in data

def test_status_requires_auth():
    """Status endpoint should return 401 without auth."""
    response = client.get("/status")
    assert response.status_code == 401

def test_history_requires_auth():
    """History endpoint should return 401 without auth."""
    response = client.get("/history")
    assert response.status_code == 401

def test_history_returns_empty_list():
    """History endpoint should return empty list when no data."""
    response = client.get("/history", headers=AUTH_HEADER)
    assert response.status_code == 200
    assert response.json() == []

def test_docker_containers_requires_auth():
    """Docker containers endpoint should return 401 without auth."""
    response = client.get("/docker/containers")
    assert response.status_code == 401

def test_invalid_token():
    """Invalid token should return 401."""
    response = client.get("/health", headers={"Authorization": "Bearer wrong"})
    assert response.status_code == 401

def test_check_auth_with_valid_header():
    """check_auth should not raise with valid token."""
    check_auth(f"Bearer {TOKEN}")

def test_check_auth_with_invalid_header():
    """check_auth should raise with invalid token."""
    with pytest.raises(Exception):
        check_auth("Bearer wrong_token")

def test_check_auth_with_none():
    """check_auth should raise with None header."""
    with pytest.raises(Exception):
        check_auth(None)

def test_health_returns_disk_percent():
    """Health endpoint should include disk_percent."""
    with patch("main.get_health_data") as mock_health:
        mock_health.return_value = {
            "hostname": "test",
            "cpu_percent": 50.0,
            "memory_percent": 60.0,
            "disk_used_gb": 100.0,
            "disk_free_gb": 200.0,
            "disk_total_gb": 300.0,
            "disk_percent": 33.3,
            "net_recv_mb": 10.0,
            "net_sent_mb": 5.0,
            "disk_read_mb": 1.0,
            "disk_write_mb": 2.0,
            "docker": {"total": 0, "running": 0, "restarting": 0, "stopped": 0}
        }
        response = client.get("/health", headers=AUTH_HEADER)
        assert response.status_code == 200
        data = response.json()
        assert "disk_percent" in data
        assert data["disk_percent"] == 33.3

def test_ws_auth_ok():
    """WebSocket should accept valid auth."""
    with client.websocket_connect("/ws/telemetry") as ws:
        ws.send_json({"type": "auth", "token": TOKEN})
        data = ws.receive_json()
        assert data.get("type") == "auth_ok"

def test_legacy_lockfile_does_not_dos():
    """Legacy lock file in /tmp must not invalidate token (prevents local DoS)."""
    import auth
    from pathlib import Path
    import tempfile
    
    legacy_file = Path(tempfile.gettempdir()) / "tgmon_token_invalidated.lock"
    try:
        legacy_file.touch(exist_ok=True)
        assert auth.is_token_valid() is True
    finally:
        try:
            legacy_file.unlink(missing_ok=True)
        except Exception:
            pass

def test_docker_cpu_multi_core_calculation():
    """CPU percent calculation should account for online_cpus."""
    from docker_api import container_stats_sync
    
    mock_container = MagicMock()
    mock_container.status = "running"
    mock_container.attrs = {'State': {'Health': {'Status': 'healthy'}}}
    mock_container.image.tags = ["myimage:latest"]
    mock_container.name = "test-container"
    mock_container.id = "abcdef1234567890"
    mock_container.ports = {}
    
    # 50% across 4 cores = 200% total core utilization
    mock_container.stats.return_value = {
        'cpu_stats': {
            'cpu_usage': {'total_usage': 2_000_000_000},
            'system_cpu_usage': 10_000_000_000,
            'online_cpus': 4
        },
        'precpu_stats': {
            'cpu_usage': {'total_usage': 1_000_000_000},
            'system_cpu_usage': 8_000_000_000
        },
        'memory_stats': {'usage': 100 * 1024 * 1024, 'limit': 1024 * 1024 * 1024},
        'networks': {}
    }
    
    stats = container_stats_sync(mock_container)
    # (1B / 2B) * 4 * 100 = 200.0%
    assert stats['cpu_percent'] == 200.0
    assert stats['health_status'] == 'healthy'

def test_secret_redaction():
    """Secrets in logs and connection strings should be redacted."""
    from docker_api import _redact_secrets
    
    raw = "DB_PASSWORD=supersecret token=abc12345xyz Bearer my-secret-jwt-token-long-enough"
    redacted = _redact_secrets(raw)
    assert "supersecret" not in redacted
    assert "***REDACTED***" in redacted

def test_health_returns_multiple_disks():
    """Health endpoint should return disks list with partitions."""
    response = client.get("/health", headers=AUTH_HEADER)
    assert response.status_code == 200
    data = response.json()
    assert "disks" in data
    assert isinstance(data["disks"], list)
    if len(data["disks"]) > 0:
        first_disk = data["disks"][0]
        assert "mountpoint" in first_disk
        assert "total_gb" in first_disk
        assert "used_gb" in first_disk
        assert "free_gb" in first_disk


