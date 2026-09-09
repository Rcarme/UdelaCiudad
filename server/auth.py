import os
import logging
import time
import stat
import tempfile
from pathlib import Path
from fastapi import Header, HTTPException
import secrets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("tgmon")

try:
    TOKEN = os.getenv('TGMON_TOKEN')
    if not TOKEN:
        logger.error("TGMON_TOKEN environment variable not defined - server will return 503")
        TOKEN = None
except Exception as e:
    logger.error(f"Failed to load TGMON_TOKEN: {e}")
    TOKEN = None

# ---------------------------------------------------------------------------
# Secure token invalidation lock file
# Previously used tempfile.gettempdir()/tgmon_token_invalidated.lock which is
# world-writable (/tmp) -> any local process could create the file and DoS
# auth by forcing is_token_valid()=False until manual delete.
# Fix: use user cache dir with 0o700 dir and 0o600 file, or sqlite in future.
# Low-resource: file-based is cheap; sqlite alternative would be heavier.
# ---------------------------------------------------------------------------

def _get_lock_file_path() -> Path:
    """Return secure lockfile path in user cache (0o700 dir, 0o600 file).

    Order:
      1. TGMON_TOKEN_LOCK_FILE env override (for tests / custom state dir)
      2. XDG_CACHE_HOME/tgmon/token_invalidated.lock
      3. ~/.cache/tgmon/token_invalidated.lock
    Falls back to secure tempfile subdir if home not writable.
    """
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
            # Fallback: create private subdir inside tempdir with 0o700
            base = Path(tempfile.gettempdir()) / f"tgmon-{os.getuid() if hasattr(os, 'getuid') else 'cache'}"

    # Ensure directory exists with 0o700 (owner only)
    try:
        base.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(base, 0o700)
        except Exception:
            pass
        # Verify permissions are not world-writable (defense in depth)
        try:
            st = base.stat()
            if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                # Try to tighten
                os.chmod(base, 0o700)
        except Exception:
            pass
    except Exception as e:
        logger.warning(f"Failed to create secure cache dir {base}: {e}")

    return base / "token_invalidated.lock"


# Legacy path for migration / cleanup (world-writable DoS vector)
_LEGACY_LOCK_FILE = Path(tempfile.gettempdir()) / "tgmon_token_invalidated.lock"

def _is_token_invalidated_file() -> bool:
    """Check if token is invalidated via secure lock file."""
    secure = _get_lock_file_path()
    if secure.exists():
        return True
    return False

def _set_token_invalidated():
    """Create lock file to invalidate token with 0o600 permissions."""
    lock_file = _get_lock_file_path()
    try:
        # Ensure parent dir exists with 0o700
        lock_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(lock_file.parent, 0o700)
        except Exception:
            pass

        # Use os.open with 0o600 to ensure atomic creation with correct mode
        # (ignores umask on most POSIX; still chmod after).
        try:
            fd = os.open(str(lock_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.close(fd)
        except Exception:
            # Fallback: Path.touch then chmod
            lock_file.touch(exist_ok=True)

        try:
            os.chmod(lock_file, 0o600)
        except Exception as e:
            logger.warning(f"Failed to chmod lock file to 0o600: {e}")

        # Extra hardening: ensure file is not world-readable
        try:
            st = lock_file.stat()
            if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                logger.warning(f"Lock file permissions too permissive: {oct(st.st_mode)}, tightening to 0o600")
                os.chmod(lock_file, 0o600)
        except Exception:
            pass

        logger.warning(f"Token invalidated (secure lock file created at {lock_file})")
    except Exception as e:
        logger.error(f"Failed to create token lock file: {e}")

def _clear_token_invalidated():
    """Remove lock file to re-enable token (secure + legacy)."""
    for p in (_get_lock_file_path(), _LEGACY_LOCK_FILE):
        try:
            if p.exists():
                p.unlink()
                logger.info(f"Token re-enabled (lock file removed at {p})")
        except Exception as e:
            logger.error(f"Failed to remove token lock file {p}: {e}")

def invalidate_token():
    """Invalidate the current token (persists across workers)."""
    _set_token_invalidated()

def is_token_valid() -> bool:
    """Check if token is still valid."""
    if _is_token_invalidated_file():
        logger.warning("Token is invalidated (lock file exists)")
        return False
    return True

def check_auth(authorization: str | None, require_fresh: bool = True) -> bool:
    if not TOKEN:
        logger.warning("Authentication rejected: TGMON_TOKEN not configured")
        raise HTTPException(status_code=503, detail='Server not configured: TGMON_TOKEN not defined')
    
    if require_fresh and not is_token_valid():
        logger.warning("Authentication rejected: token has been invalidated")
        raise HTTPException(status_code=401, detail='Token invalidated, please re-authenticate')
    
    if not authorization or not authorization.startswith('Bearer '):
        logger.warning("Authentication failed: missing or invalid Authorization header")
        raise HTTPException(status_code=401, detail='Unauthorized')
    
    token = authorization.split(' ', 1)[1]
    if not secrets.compare_digest(token, TOKEN):
        logger.warning("Authentication failed: invalid token")
        raise HTTPException(status_code=401, detail='Unauthorized')
    
    return True

def reenable_token():
    """Re-enable the token (remove lock file)."""
    _clear_token_invalidated()

def cleanup_token_lock():
    """Remove stale token lock file if it exists (>86400s old)."""
    for lock_file in (_get_lock_file_path(), _LEGACY_LOCK_FILE):
        try:
            if lock_file.exists():
                file_age = time.time() - lock_file.stat().st_mtime
                if file_age > 86400:
                    try:
                        lock_file.unlink()
                        logger.info(f"Removed stale token lock file at {lock_file}")
                    except Exception as e:
                        logger.error(f"Failed to remove stale lock {lock_file}: {e}")
        except Exception:
            pass

# For backwards compat, expose legacy var as secure path (old code may import it)
_TOKEN_LOCK_FILE = str(_get_lock_file_path())
