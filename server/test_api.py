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

def test_container_id_validation_valid():
    """Valid hex container IDs (12-64 chars) should pass validation."""
    from docker_api import validate_container_id
    assert validate_container_id("1234567890ab") is True
    assert validate_container_id("abcdef1234567890abcdef1234567890") is True
    assert validate_container_id("a" * 64) is True

def test_container_id_validation_invalid():
    """Invalid container IDs (shell metachars, non-hex, too short) must fail."""
    from docker_api import validate_container_id
    assert validate_container_id("") is False
    assert validate_container_id("12345") is False  # too short (<12)
    assert validate_container_id("abcdef12345g") is False  # 'g' is not hex
    assert validate_container_id("1234567890ab; rm -rf /") is False  # command injection
    assert validate_container_id("$(whoami)12345") is False  # shell substitution
    assert validate_container_id("a" * 65) is False  # too long (>64)

def test_auth_check_order_header_first():
    """Unauthenticated requests must get 401 Unauthorized without probing invalidated status."""
    import auth
    auth.invalidate_token()
    try:
        # Request with no header must return 401 Unauthorized
        response = client.get("/health")
        assert response.status_code == 401
        assert response.json()["detail"] == "Unauthorized"
    finally:
        auth.reenable_token()

def test_auth_invalidation_and_reenable_endpoints():
    """POST /auth/invalidate should block access, POST /auth/reenable should restore access."""
    # Invalidate token
    inv_response = client.post("/auth/invalidate", headers=AUTH_HEADER)
    assert inv_response.status_code == 200
    assert inv_response.json()["success"] is True

    # Subsequent request should be rejected as invalidated
    rejected = client.get("/health", headers=AUTH_HEADER)
    assert rejected.status_code == 401
    assert "invalidated" in rejected.json()["detail"]

    # Re-enable token
    reenable_response = client.post("/auth/reenable", headers=AUTH_HEADER)
    assert reenable_response.status_code == 200
    assert reenable_response.json()["success"] is True

    # Access is restored
    with patch("main.get_health_data") as mock_health:
        mock_health.return_value = {"hostname": "restored", "cpu_percent": 10.0, "memory_percent": 20.0, "disk_used_gb": 1.0, "disk_free_gb": 1.0, "disk_total_gb": 2.0}
        restored = client.get("/health", headers=AUTH_HEADER)
        assert restored.status_code == 200

def test_verify_auth_endpoint():
    """GET /auth/verify should confirm valid token."""
    response = client.get("/auth/verify", headers=AUTH_HEADER)
    assert response.status_code == 200
    assert response.json()["valid"] is True

def test_ws_handshake_rejects_invalid_token():
    """WebSocket should reject connection with invalid token."""
    with pytest.raises(Exception):
        with client.websocket_connect("/ws/telemetry") as ws:
            ws.send_json({"type": "auth", "token": "wrong_token_value"})
            ws.receive_json()

def test_ws_handshake_rejects_invalidated_token():
    """WebSocket should reject connection if token is invalidated."""
    import auth
    auth.invalidate_token()
    try:
        with pytest.raises(Exception):
            with client.websocket_connect("/ws/telemetry") as ws:
                ws.send_json({"type": "auth", "token": TOKEN})
                ws.receive_json()
    finally:
        auth.reenable_token()

def test_ws_ping_pong():
    """WebSocket should respond to ping with pong."""
    with client.websocket_connect("/ws/telemetry") as ws:
        ws.send_json({"type": "auth", "token": TOKEN})
        auth_ack = ws.receive_json()
        assert auth_ack.get("type") == "auth_ok"
        ws.send_text("ping")
        response = ws.receive_text()
        assert response == "pong"

def test_history_range_validation():
    """Hours out of bounds (1..8760) should return HTTP 400."""
    res_zero = client.get("/history?hours=0", headers=AUTH_HEADER)
    assert res_zero.status_code == 400

    res_negative = client.get("/history?hours=-5", headers=AUTH_HEADER)
    assert res_negative.status_code == 400

    res_too_large = client.get("/history?hours=100000", headers=AUTH_HEADER)
    assert res_too_large.status_code == 400

def test_history_csv_export():
    """GET /history/csv should return CSV text header."""
    response = client.get("/history/csv", headers=AUTH_HEADER)
    assert response.status_code == 200
    assert "timestamp,cpu_percent,memory_percent" in response.text

def test_status_endpoint_structure():
    """GET /status should return uptime, load_avg, docker_active, ssh_active."""
    response = client.get("/status", headers=AUTH_HEADER)
    assert response.status_code == 200
    data = response.json()
    assert "uptime_seconds" in data
    assert "load_avg" in data
    assert "docker_active" in data
    assert "ssh_active" in data
    assert isinstance(data["docker_active"], bool)
    assert isinstance(data["ssh_active"], bool)

def test_db_cleanup_old_metrics():
    """cleanup_old_metrics should remove records older than retention period."""
    import db
    import time
    conn = db.get_connection()
    # Insert metric from 35 days ago (in ms)
    old_time_ms = int((time.time() - (35 * 24 * 3600)) * 1000)
    conn.execute(
        "INSERT INTO metrics (timestamp, cpu_percent, memory_percent, disk_used_gb, disk_free_gb, disk_total_gb, disk_percent) VALUES (?, ?, ?, ?, ?, ?, ?)",
        (old_time_ms, 10.0, 20.0, 30.0, 40.0, 70.0, 42.8)
    )
    conn.commit()

    db.cleanup_old_metrics(days=30)
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM metrics WHERE timestamp = ?", (old_time_ms,))
    assert cursor.fetchone()[0] == 0

def test_verify_auth_endpoint_returns_role():
    """GET /auth/verify should return valid=True and role=admin for admin token."""
    response = client.get("/auth/verify", headers=AUTH_HEADER)
    assert response.status_code == 200
    data = response.json()
    assert data["valid"] is True
    assert data["role"] == "admin"

def test_viewer_token_rbac_permissions():
    """Viewer token should allow read endpoints but reject mutating endpoints with 403."""
    import auth
    # Configure viewer token
    auth.VIEWER_TOKEN = "viewer_token_456"
    viewer_header = {"Authorization": "Bearer viewer_token_456"}

    try:
        # Verify reports viewer role
        verify_res = client.get("/auth/verify", headers=viewer_header)
        assert verify_res.status_code == 200
        assert verify_res.json()["role"] == "viewer"

        # Read endpoint /status allowed for viewer
        status_res = client.get("/status", headers=viewer_header)
        assert status_res.status_code == 200

        # Read endpoint /health allowed for viewer
        with patch("main.get_health_data") as mock_health:
            mock_health.return_value = {"hostname": "test", "cpu_percent": 1.0, "memory_percent": 1.0, "disk_used_gb": 1.0, "disk_free_gb": 1.0, "disk_total_gb": 2.0}
            health_res = client.get("/health", headers=viewer_header)
            assert health_res.status_code == 200

        # Mutating endpoint /restart FORBIDDEN (403) for viewer
        restart_res = client.post("/docker/containers/1234567890ab/restart", headers=viewer_header)
        assert restart_res.status_code == 403
        assert "Forbidden" in restart_res.json()["detail"]

        # Mutating endpoint /auth/invalidate FORBIDDEN (403) for viewer
        inv_res = client.post("/auth/invalidate", headers=viewer_header)
        assert inv_res.status_code == 403
        assert "Forbidden" in inv_res.json()["detail"]
    finally:
        auth.VIEWER_TOKEN = None






def test_ai_predict_spike_requires_auth():
    """GET /ai/predict-spike should return 401 without auth."""
    response = client.get("/ai/predict-spike")
    assert response.status_code == 401


def test_ai_predict_spike_with_auth():
    """GET /ai/predict-spike should return valid risk_level and message."""
    response = client.get("/ai/predict-spike", headers=AUTH_HEADER)
    assert response.status_code == 200
    data = response.json()
    assert "risk_level" in data
    assert data["risk_level"] in ["low", "medium", "moderate", "high", "critical"]
    assert "message" in data
    assert isinstance(data["message"], str)
    assert "cpu_usage" in data
    assert "memory_usage" in data


def test_ai_recommendations_requires_auth():
    """GET /ai/recommendations should return 401 without auth."""
    response = client.get("/ai/recommendations")
    assert response.status_code == 401


def test_ai_recommendations_with_auth():
    """GET /ai/recommendations should return recommendations list."""
    response = client.get("/ai/recommendations", headers=AUTH_HEADER)
    assert response.status_code == 200
    data = response.json()
    assert "recommendations" in data
    assert isinstance(data["recommendations"], list)
    assert len(data["recommendations"]) > 0
    rec = data["recommendations"][0]
    assert "container_id" in rec
    assert "container_name" in rec
    assert "recommended_action" in rec
    assert "reason" in rec
