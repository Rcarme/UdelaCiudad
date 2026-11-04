import logging
import os
import secrets
import stat
import tempfile
import time
from pathlib import Path
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

logger = logging.getLogger("tgmon.auth")

ROLE_ADMIN = "admin"
ROLE_VIEWER = "viewer"

ADMIN_TOKEN: str | None = os.getenv("TGMON_ADMIN_TOKEN") or os.getenv("TGMON_TOKEN")
VIEWER_TOKEN: str | None = os.getenv("TGMON_VIEWER_TOKEN")
TOKEN: str | None = ADMIN_TOKEN

if not ADMIN_TOKEN and not VIEWER_TOKEN:
    logger.warning(
        "Neither TGMON_ADMIN_TOKEN nor TGMON_TOKEN environment variable defined - server will return 503"
    )

_bearer_scheme = HTTPBearer(auto_error=False)


def _resolve_tokens() -> tuple[str | None, str | None]:
    """Return current (admin_token, viewer_token) from environment or module state."""
    admin_tok = os.getenv("TGMON_ADMIN_TOKEN") or os.getenv("TGMON_TOKEN") or ADMIN_TOKEN
    viewer_tok = os.getenv("TGMON_VIEWER_TOKEN") or VIEWER_TOKEN
    return admin_tok, viewer_tok


def _safe_compare(provided: str, expected: str) -> bool:
    """Constant-time comparison supporting arbitrary UTF-8 strings without TypeError."""
    return secrets.compare_digest(
        provided.encode("utf-8", errors="replace"),
        expected.encode("utf-8", errors="replace"),
    )


def _get_lock_file_path(create_dir: bool = False) -> Path:
    """Return secure lockfile path in user cache (0o700 dir, 0o600 file)."""
    override = os.getenv("TGMON_TOKEN_LOCK_FILE")
    if override:
        return Path(override)

    xdg = os.getenv("XDG_CACHE_HOME")
    if xdg:
        base = Path(xdg) / "tgmon"
    else:
        try:
            base = Path.home() / ".cache" / "tgmon"
        except Exception:
            uid = os.getuid() if hasattr(os, "getuid") else "cache"
            base = Path(tempfile.gettempdir()) / f"tgmon-{uid}"

    if create_dir:
        try:
            base.mkdir(parents=True, exist_ok=True)
            try:
                os.chmod(base, 0o700)
                st = base.stat()
                if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                    os.chmod(base, 0o700)
            except OSError:
                pass
        except OSError as exc:
            logger.warning("Failed to create secure cache dir %s: %s", base, exc)

    return base / "token_invalidated.lock"


def _is_token_invalidated_file() -> bool:
    """Check if token is invalidated via secure lock file."""
    return _get_lock_file_path(create_dir=False).exists()


def _set_token_invalidated() -> None:
    """Create lock file to invalidate token with 0o600 permissions."""
    lock_file = _get_lock_file_path(create_dir=True)
    try:
        try:
            fd = os.open(str(lock_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.close(fd)
        except OSError:
            lock_file.touch(exist_ok=True)

        try:
            os.chmod(lock_file, 0o600)
            st = lock_file.stat()
            if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                os.chmod(lock_file, 0o600)
        except OSError as exc:
            logger.warning("Failed to chmod lock file to 0o600: %s", exc)

        logger.warning("Token invalidated (lock file created at %s)", lock_file)
    except OSError as exc:
        logger.error("Failed to create token lock file: %s", exc)


def _clear_token_invalidated() -> None:
    """Remove lock file to re-enable token."""
    lock_file = _get_lock_file_path(create_dir=False)
    try:
        if lock_file.exists():
            lock_file.unlink()
            logger.info("Token re-enabled (lock file removed at %s)", lock_file)
    except OSError as exc:
        logger.error("Failed to remove token lock file %s: %s", lock_file, exc)


def invalidate_token() -> None:
    """Invalidate the current token (persists across workers)."""
    _set_token_invalidated()


def is_token_valid() -> bool:
    """Check if token is still valid."""
    if _is_token_invalidated_file():
        logger.warning("Token is invalidated (lock file exists)")
        return False
    return True


def get_token_role(token: str | None) -> str | None:
    """Return 'admin', 'viewer', or None using UTF-8 safe constant-time comparison."""
    if not token or not isinstance(token, str):
        return None
    admin_tok, viewer_tok = _resolve_tokens()

    if admin_tok and _safe_compare(token, admin_tok):
        return ROLE_ADMIN
    if viewer_tok and _safe_compare(token, viewer_tok):
        return ROLE_VIEWER
    return None


def check_auth(
    authorization: str | None,
    require_fresh: bool = True,
    required_role: str = ROLE_VIEWER,
    client_ip: str | None = None,
) -> str:
    """Validate Authorization header, token freshness, and required role."""
    admin_tok, viewer_tok = _resolve_tokens()
    ip_ctx = f" (ip={client_ip})" if client_ip else ""

    if not admin_tok and not viewer_tok:
        logger.warning("Authentication rejected%s: TGMON_ADMIN_TOKEN not configured", ip_ctx)
        raise HTTPException(status_code=503, detail="Server not configured: TGMON_TOKEN not defined")

    if not authorization or not isinstance(authorization, str):
        logger.warning("Authentication failed%s: missing Authorization header", ip_ctx)
        raise HTTPException(status_code=401, detail="Unauthorized")

    parts = authorization.strip().split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].strip():
        logger.warning("Authentication failed%s: invalid Authorization scheme", ip_ctx)
        raise HTTPException(status_code=401, detail="Unauthorized")

    token = parts[1].strip()
    role = get_token_role(token)
    if not role:
        logger.warning("Authentication failed%s: invalid token", ip_ctx)
        raise HTTPException(status_code=401, detail="Unauthorized")

    if require_fresh and not is_token_valid():
        logger.warning("Authentication rejected%s: token has been invalidated", ip_ctx)
        raise HTTPException(status_code=401, detail="Token invalidated, please re-authenticate")

    if required_role == ROLE_ADMIN and role != ROLE_ADMIN:
        logger.warning(
            "Forbidden%s: action requires role %s, but token role is %s",
            ip_ctx,
            ROLE_ADMIN,
            role,
        )
        raise HTTPException(status_code=403, detail="Forbidden: Admin privileges required")

    return role


def require_role(required_role: str = ROLE_VIEWER, require_fresh: bool = True):
    """FastAPI Dependency integrating HTTPBearer (OpenAPI Authorize button) with RBAC."""
    def _dep(
        request: Request,
        credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
    ) -> str:
        raw_header = request.headers.get("Authorization")
        if not raw_header and credentials:
            raw_header = f"{credentials.scheme} {credentials.credentials}"
        client_ip = request.client.host if getattr(request, "client", None) else None
        return check_auth(
            raw_header,
            require_fresh=require_fresh,
            required_role=required_role,
            client_ip=client_ip,
        )

    return _dep


def reenable_token() -> None:
    """Re-enable the token (remove lock file)."""
    _clear_token_invalidated()


def cleanup_token_lock() -> None:
    """Remove stale token lock file if it exists (>86400s old)."""
    lock_file = _get_lock_file_path(create_dir=False)
    try:
        if lock_file.exists():
            file_age = time.time() - lock_file.stat().st_mtime
            if file_age > 86400:
                lock_file.unlink()
                logger.info("Removed stale token lock file at %s", lock_file)
    except OSError as exc:
        logger.error("Failed to remove stale lock %s: %s", lock_file, exc)
