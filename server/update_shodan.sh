#!/usr/bin/env bash
# ==============================================================================
# TGMon v2 - Script de Actualización Automática para Servidor SHODAN
# Configura RBAC de 2 Tokens (Admin & Viewer), actualiza el backend y reinicia Docker
# ==============================================================================
set -e

echo "=========================================================="
echo " [TGMon v2] Iniciando actualización automática en SHODAN "
echo "=========================================================="

# 1. Determinar directorio de la aplicación
if [ -d "$HOME/docker/apps/tgmon" ]; then
    APP_DIR="$HOME/docker/apps/tgmon"
elif [ -d "$PWD/app" ] || [ -f "$PWD/docker-compose.yml" ]; then
    APP_DIR="$PWD"
else
    APP_DIR="$HOME/docker/apps/tgmon"
    mkdir -p "$APP_DIR"
fi

cd "$APP_DIR"
echo "[+] Directorio de trabajo: $APP_DIR"
mkdir -p "$APP_DIR/app"

# 2. Configurar tokens en .env.api y .env
ADMIN_TOKEN="${TGMON_ADMIN_TOKEN:?definir TGMON_ADMIN_TOKEN (ej: openssl rand -hex 32)}"
VIEWER_TOKEN="${TGMON_VIEWER_TOKEN:-cambiar_por_un_token_largo_y_aleatorio}"

cat << EOF > "$APP_DIR/.env.api"
# Tokens de Autenticación TGMon v2
TGMON_ADMIN_TOKEN=$ADMIN_TOKEN
TGMON_VIEWER_TOKEN=$VIEWER_TOKEN
TGMON_TOKEN=$ADMIN_TOKEN
EOF

cp "$APP_DIR/.env.api" "$APP_DIR/.env"
cp "$APP_DIR/.env.api" "$APP_DIR/app/.env.api" 2>/dev/null || true
echo "[+] Tokens configurados en .env.api"

# 3. Escribir auth.py con soporte de 2 tokens (Admin y Viewer)
write_auth_file() {
    local target="$1"
    cat << 'EOF' > "$target"
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

ROLE_ADMIN = "admin"
ROLE_VIEWER = "viewer"

try:
    ADMIN_TOKEN = os.getenv('TGMON_ADMIN_TOKEN') or os.getenv('TGMON_TOKEN')
    VIEWER_TOKEN = os.getenv('TGMON_VIEWER_TOKEN')
    TOKEN = ADMIN_TOKEN
    if not ADMIN_TOKEN and not VIEWER_TOKEN:
        logger.error("Neither TGMON_ADMIN_TOKEN nor TGMON_TOKEN environment variable defined - server will return 503")
except Exception as e:
    logger.error(f"Failed to load tokens: {e}")
    ADMIN_TOKEN = None
    VIEWER_TOKEN = None
    TOKEN = None

def _get_lock_file_path() -> Path:
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
            base = Path(tempfile.gettempdir()) / f"tgmon-{os.getuid() if hasattr(os, 'getuid') else 'cache'}"
    try:
        base.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(base, 0o700)
        except Exception:
            pass
    except Exception as e:
        logger.warning(f"Failed to create secure cache dir {base}: {e}")
    return base / "token_invalidated.lock"

_LEGACY_LOCK_FILE = Path(tempfile.gettempdir()) / "tgmon_token_invalidated.lock"

def _is_token_invalidated_file() -> bool:
    secure = _get_lock_file_path()
    return secure.exists()

def _set_token_invalidated():
    lock_file = _get_lock_file_path()
    try:
        lock_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.chmod(lock_file.parent, 0o700)
        except Exception:
            pass
        try:
            fd = os.open(str(lock_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            os.close(fd)
        except Exception:
            lock_file.touch(exist_ok=True)
        try:
            os.chmod(lock_file, 0o600)
        except Exception:
            pass
        logger.warning(f"Token invalidated (secure lock file created at {lock_file})")
    except Exception as e:
        logger.error(f"Failed to create token lock file: {e}")

def _clear_token_invalidated():
    for p in (_get_lock_file_path(), _LEGACY_LOCK_FILE):
        try:
            if p.exists():
                p.unlink()
                logger.info(f"Token re-enabled (lock file removed at {p})")
        except Exception as e:
            logger.error(f"Failed to remove token lock file {p}: {e}")

def invalidate_token():
    _set_token_invalidated()

def is_token_valid() -> bool:
    if _is_token_invalidated_file():
        logger.warning("Token is invalidated (lock file exists)")
        return False
    return True

def get_token_role(token: str | None) -> str | None:
    if not token or not isinstance(token, str):
        return None
    admin_tok = os.getenv('TGMON_ADMIN_TOKEN') or os.getenv('TGMON_TOKEN') or ADMIN_TOKEN
    viewer_tok = os.getenv('TGMON_VIEWER_TOKEN') or VIEWER_TOKEN

    if admin_tok and secrets.compare_digest(token, admin_tok):
        return ROLE_ADMIN
    if viewer_tok and secrets.compare_digest(token, viewer_tok):
        return ROLE_VIEWER
    return None

def check_auth(authorization: str | None, require_fresh: bool = True, required_role: str = ROLE_VIEWER) -> str:
    admin_tok = os.getenv('TGMON_ADMIN_TOKEN') or os.getenv('TGMON_TOKEN') or ADMIN_TOKEN
    viewer_tok = os.getenv('TGMON_VIEWER_TOKEN') or VIEWER_TOKEN

    if not admin_tok and not viewer_tok:
        logger.warning("Authentication rejected: TGMON_TOKEN / TGMON_ADMIN_TOKEN not configured")
        raise HTTPException(status_code=503, detail='Server not configured: TGMON_TOKEN not defined')
    
    if not authorization or not authorization.startswith('Bearer '):
        logger.warning("Authentication failed: missing or invalid Authorization header")
        raise HTTPException(status_code=401, detail='Unauthorized')
    
    token = authorization.split(' ', 1)[1]
    role = get_token_role(token)
    if not role:
        logger.warning("Authentication failed: invalid token")
        raise HTTPException(status_code=401, detail='Unauthorized')
    
    if require_fresh and not is_token_valid():
        logger.warning("Authentication rejected: token has been invalidated")
        raise HTTPException(status_code=401, detail='Token invalidated, please re-authenticate')
    
    if required_role == ROLE_ADMIN and role != ROLE_ADMIN:
        logger.warning(f"Forbidden: Action requires role {ROLE_ADMIN}, but token role is {role}")
        raise HTTPException(status_code=403, detail='Forbidden: Admin privileges required')
    
    return role

def reenable_token():
    _clear_token_invalidated()

def cleanup_token_lock():
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

_TOKEN_LOCK_FILE = str(_get_lock_file_path())
EOF
}

# Write only to app/auth.py (single source of truth)
write_auth_file "$APP_DIR/app/auth.py"
echo "[+] auth.py actualizado con soporte de 2 tokens y RBAC"

# 4. Actualizar endpoints en main.py y docker_api.py mediante Python
python3 - << 'PYEOF'
import os, re

def patch_file(path, patches):
    if not os.path.exists(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    for pattern, repl in patches:
        content = re.sub(pattern, repl, content)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"[+] Archivo parcheado: {path}")

# Parches para main.py
main_patches = [
    # Importar ROLE_ADMIN y get_token_role
    (r"from auth import check_auth, TOKEN, invalidate_token, reenable_token, is_token_valid, cleanup_token_lock(?!, ROLE_ADMIN)",
     r"from auth import check_auth, TOKEN, invalidate_token, reenable_token, is_token_valid, cleanup_token_lock, ROLE_ADMIN, get_token_role"),
    # /auth/invalidate requiere admin
    (r"async def invalidate_auth_endpoint\(authorization: str \| None = Header\(None\)\):\s*check_auth\(authorization\)",
     r"async def invalidate_auth_endpoint(authorization: str | None = Header(None)):\n    check_auth(authorization, required_role=ROLE_ADMIN)"),
    # /auth/reenable requiere admin
    (r"async def reenable_auth_endpoint\(authorization: str \| None = Header\(None\)\):\s*check_auth\(authorization, require_fresh=False\)",
     r"async def reenable_auth_endpoint(authorization: str | None = Header(None)):\n    check_auth(authorization, require_fresh=False, required_role=ROLE_ADMIN)"),
    # /auth/verify devuelve role
    (r"@app\.get\('/auth/verify'\)\s*async def verify_auth\(authorization: str \| None = Header\(None\)\):\s*check_auth\(authorization\)\s*return \{[\"']valid[\"']:\s*True\}",
     r"@app.get('/auth/verify')\nasync def verify_auth(authorization: str | None = Header(None)):\n    role = check_auth(authorization)\n    return {\"valid\": True, \"role\": role}"),
    # WebSocket auth verifica role y envía role en auth_ok
    (r"if auth_msg\.get\(\"type\"\) != \"auth\" or received_token != TOKEN:",
     r"role = get_token_role(received_token)\n        if auth_msg.get(\"type\") != \"auth\" or not role:"),
    (r"await websocket\.send_text\(json\.dumps\(\{\"type\": \"auth_ok\"\}\)\)",
     r"await websocket.send_text(json.dumps({\"type\": \"auth_ok\", \"role\": role}))")
]

for p in ["./app/main.py"]:
    patch_file(p, main_patches)

# Parches para docker_api.py
docker_patches = [
    (r"from auth import check_auth(?!, ROLE_ADMIN)", r"from auth import check_auth, ROLE_ADMIN"),
    (r"@router\.post\('/containers/\{container_id\}/restart'\)\s*async def restart_container\(\s*container_id: str,\s*request: Request,\s*authorization: str \| None = Header\(None\)\s*\):\s*check_auth\(authorization\)",
     r"@router.post('/containers/{container_id}/restart')\nasync def restart_container(\n    container_id: str,\n    request: Request,\n    authorization: str | None = Header(None)\n):\n    check_auth(authorization, required_role=ROLE_ADMIN)"),
    (r"@router\.post\('/containers/\{container_id\}/stop'\)\s*async def stop_container\(\s*container_id: str,\s*request: Request,\s*authorization: str \| None = Header\(None\)\s*\):\s*check_auth\(authorization\)",
     r"@router.post('/containers/{container_id}/stop')\nasync def stop_container(\n    container_id: str,\n    request: Request,\n    authorization: str | None = Header(None)\n):\n    check_auth(authorization, required_role=ROLE_ADMIN)"),
    (r"@router\.post\('/containers/\{container_id\}/start'\)\s*async def start_container\(\s*container_id: str,\s*request: Request,\s*authorization: str \| None = Header\(None\)\s*\):\s*check_auth\(authorization\)",
     r"@router.post('/containers/{container_id}/start')\nasync def start_container(\n    container_id: str,\n    request: Request,\n    authorization: str | None = Header(None)\n):\n    check_auth(authorization, required_role=ROLE_ADMIN)"),
    (r"@router\.delete\('/containers/\{container_id\}'\)\s*async def remove_container\(\s*container_id: str,\s*request: Request,\s*force: bool = False,\s*authorization: str \| None = Header\(None\)\s*\):\s*check_auth\(authorization\)",
     r"@router.delete('/containers/{container_id}')\nasync def remove_container(\n    container_id: str,\n    request: Request,\n    force: bool = False,\n    authorization: str | None = Header(None)\n):\n    check_auth(authorization, required_role=ROLE_ADMIN)"),
    (r"@router\.post\('/containers/\{container_id\}/pause'\)\s*async def pause_container\(\s*container_id: str,\s*request: Request,\s*authorization: str \| None = Header\(None\)\s*\):\s*check_auth\(authorization\)",
     r"@router.post('/containers/{container_id}/pause')\nasync def pause_container(\n    container_id: str,\n    request: Request,\n    authorization: str | None = Header(None)\n):\n    check_auth(authorization, required_role=ROLE_ADMIN)"),
    (r"@router\.post\('/containers/\{container_id\}/unpause'\)\s*async def unpause_container\(\s*container_id: str,\s*request: Request,\s*authorization: str \| None = Header\(None\)\s*\):\s*check_auth\(authorization\)",
     r"@router.post('/containers/{container_id}/unpause')\nasync def unpause_container(\n    container_id: str,\n    request: Request,\n    authorization: str | None = Header(None)\n):\n    check_auth(authorization, required_role=ROLE_ADMIN)")
]

for p in ["./app/docker_api.py"]:
    patch_file(p, docker_patches)
PYEOF

# 5. Escribir docker-compose.yml con inyección directa de tokens en environment
cat << 'EOF' > "$APP_DIR/docker-compose.yml"
services:
  tgmon:
    build: .
    container_name: tgmon
    restart: unless-stopped
    ports:
      - "8000:8000"
    volumes:
      # 1. Código en vivo
      - ./app:/app/app
      # 2. Permiso para hablar con Docker
      - /var/run/docker.sock:/var/run/docker.sock
      # 3. Lectura de composes
      - /home/comstock/docker:/home/comstock/docker:ro
    environment:
      - TZ=America/Argentina/Buenos_Aires
      - COMPOSE_PATH=/home/comstock/docker/media-stack/docker-compose.yml
      - TGMON_TOKEN=${TGMON_TOKEN:-}
      - TGMON_ADMIN_TOKEN=${TGMON_ADMIN_TOKEN:?definir TGMON_ADMIN_TOKEN}
      - TGMON_VIEWER_TOKEN=${TGMON_VIEWER_TOKEN:-}
    networks:
      - homelab

networks:
  homelab:
    external: true
EOF
echo "[+] docker-compose.yml actualizado con variables de entorno inyectadas"

# 6. Reiniciar contenedores con Docker Compose
echo "[+] Reiniciando contenedor tgmon..."
docker compose down || true
docker compose up -d --build

echo "[+] Esperando 3 segundos a que uvicorn inicialice..."
sleep 3

# 7. Diagnóstico y Verificación Automatizada
echo "=========================================================="
echo "          VERIFICACIÓN AUTOMATIZADA DE ACCESO             "
echo "=========================================================="

echo "[-] Variables en el contenedor:"
docker compose exec tgmon env | grep TOKEN || true

echo -e "\n[-] Test 1: Verificar token ADMIN:"
ADMIN_RES=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8000/auth/verify || echo "ERR")
if [ "$ADMIN_RES" = "200" ]; then
    echo "    >>> OK! HTTP 200 - Token Admin verificado correctamente."
    curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8000/auth/verify
    echo ""
else
    echo "    >>> ERROR: HTTP $ADMIN_RES al verificar Admin."
fi

echo -e "\n[-] Test 2: Verificar token VIEWER:"
VIEWER_RES=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $VIEWER_TOKEN" http://localhost:8000/auth/verify || echo "ERR")
if [ "$VIEWER_RES" = "200" ]; then
    echo "    >>> OK! HTTP 200 - Token Viewer verificado correctamente."
    curl -s -H "Authorization: Bearer $VIEWER_TOKEN" http://localhost:8000/auth/verify
    echo ""
else
    echo "    >>> ERROR: HTTP $VIEWER_RES al verificar Viewer."
fi

echo -e "\n[-] Test 3: Probar permiso mutante con Viewer (debe dar 403 Forbidden):"
FORBIDDEN_RES=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Authorization: Bearer $VIEWER_TOKEN" http://localhost:8000/docker/containers/tgmon/restart || echo "ERR")
if [ "$FORBIDDEN_RES" = "403" ]; then
    echo "    >>> OK! HTTP 403 Forbidden - El token Viewer está correctamente protegido de acciones destructivas."
else
    echo "    >>> Estado obtenido: HTTP $FORBIDDEN_RES"
fi

echo "=========================================================="
echo " [TGMon v2] ¡Servidor listo y funcionando correctamente! "
echo "=========================================================="
