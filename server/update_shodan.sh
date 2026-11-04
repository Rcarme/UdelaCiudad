#!/usr/bin/env bash
# ==============================================================================
# TGMon v2 - Script de Actualización y Configuración Segura para Servidor SHODAN
# Configura credenciales RBAC (Admin y Viewer opcional), protege permisos de
# archivos de entorno y verifica el despliegue con Docker Compose sin exponer
# secretos en la salida de la terminal ni sobrescribir módulos de producción.
# ==============================================================================
set -euo pipefail

echo "=========================================================="
echo " [TGMon v2] Iniciando actualización segura del servidor   "
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

# 2. Validación de credenciales (rechazar valores vacíos o ejemplos conocidos)
is_known_placeholder() {
    local val="$1"
    case "$val" in
        ""|"cambiar_por_un_token_largo_y_aleatorio"|shodan_*_2026|"TU_TOKEN"|"your_token_here"|"test_secret_token")
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

ADMIN_TOKEN="${TGMON_ADMIN_TOKEN:-}"
VIEWER_TOKEN="${TGMON_VIEWER_TOKEN:-}"

if is_known_placeholder "$ADMIN_TOKEN"; then
    echo "[!] ERROR: TGMON_ADMIN_TOKEN no está definido o utiliza un valor de ejemplo público." >&2
    echo "    Genere un token aleatorio (ej: openssl rand -hex 32) y expórtelo antes de ejecutar este script." >&2
    exit 1
fi

if [ "${#ADMIN_TOKEN}" -lt 12 ]; then
    echo "[!] ERROR: TGMON_ADMIN_TOKEN debe tener al menos 12 caracteres." >&2
    exit 1
fi

if [ -n "$VIEWER_TOKEN" ]; then
    if is_known_placeholder "$VIEWER_TOKEN"; then
        echo "[!] ERROR: TGMON_VIEWER_TOKEN utiliza un valor de ejemplo público no permitido." >&2
        echo "    Defina un token único o déjelo vacío para deshabilitar el rol viewer." >&2
        exit 1
    fi
    if [ "${#VIEWER_TOKEN}" -lt 12 ]; then
        echo "[!] ERROR: TGMON_VIEWER_TOKEN debe tener al menos 12 caracteres cuando se habilita." >&2
        exit 1
    fi
    if [ "$VIEWER_TOKEN" = "$ADMIN_TOKEN" ]; then
        echo "[!] ERROR: TGMON_VIEWER_TOKEN debe ser distinto de TGMON_ADMIN_TOKEN." >&2
        exit 1
    fi
    VIEWER_ROLE_ENABLED=1
    echo "[+] Rol ADMIN: habilitado | Rol VIEWER: habilitado"
else
    # Si no se proporciona TGMON_VIEWER_TOKEN, el rol viewer queda explícitamente deshabilitado
    VIEWER_TOKEN=""
    VIEWER_ROLE_ENABLED=0
    echo "[+] Rol ADMIN: habilitado | Rol VIEWER: deshabilitado (sin credencial configurada)"
fi

# Preservar GEMINI_API_KEY existente si no se pasó por entorno
EXISTING_GEMINI_KEY="${GEMINI_API_KEY:-}"
if [ -z "$EXISTING_GEMINI_KEY" ] && [ -f "$APP_DIR/.env" ]; then
    EXISTING_GEMINI_KEY=$(grep -E '^GEMINI_API_KEY=' "$APP_DIR/.env" | tail -n 1 | cut -d'=' -f2- || true)
fi

# 3. Escribir archivos de entorno con permisos restrictivos (0600)
(
    umask 077
    cat << EOF > "$APP_DIR/.env.api"
# Credenciales de Autenticación TGMon v2 (permisos 0600)
TGMON_ADMIN_TOKEN=$ADMIN_TOKEN
TGMON_VIEWER_TOKEN=$VIEWER_TOKEN
TGMON_TOKEN=$ADMIN_TOKEN
GEMINI_API_KEY=$EXISTING_GEMINI_KEY
EOF
    chmod 600 "$APP_DIR/.env.api"
    cp -f "$APP_DIR/.env.api" "$APP_DIR/.env"
    chmod 600 "$APP_DIR/.env"
    cp -f "$APP_DIR/.env.api" "$APP_DIR/app/.env.api"
    chmod 600 "$APP_DIR/app/.env.api"
)
echo "[+] Archivos .env y .env.api actualizados con permisos restrictivos (0600)."

# 4. Verificar que los módulos de producción en app/ existan sin sobrescribirlos
REQUIRED_MODULES=("main.py" "auth.py" "ai.py" "models.py" "db.py" "docker_api.py")
for mod in "${REQUIRED_MODULES[@]}"; do
    if [ ! -f "$APP_DIR/app/$mod" ]; then
        echo "[!] ERROR: Falta el módulo requerido $APP_DIR/app/$mod." >&2
        exit 1
    fi
    python3 -c "import py_compile; py_compile.compile('$APP_DIR/app/$mod', doraise=True)" >/dev/null
done
echo "[+] Módulos de producción en app/ verificados (sintaxis OK, sin sobrescritura)."

# 5. Asegurar docker-compose.yml compatible si no existe
if [ ! -f "$APP_DIR/docker-compose.yml" ]; then
    cat << 'EOF' > "$APP_DIR/docker-compose.yml"
services:
  tgmon:
    build: .
    container_name: tgmon
    restart: unless-stopped
    ports:
      - "8000:8000"
    volumes:
      - ./app:/app/app
      - /var/run/docker.sock:/var/run/docker.sock
      - /home/comstock/docker:/home/comstock/docker:ro
      - /media:/media:ro
      - /mnt:/mnt:ro
      - /run/media:/run/media:ro
    environment:
      - TZ=America/Argentina/Buenos_Aires
      - COMPOSE_PATH=/home/comstock/docker/media-stack/docker-compose.yml
      - TGMON_TOKEN=${TGMON_TOKEN:-}
      - TGMON_ADMIN_TOKEN=${TGMON_ADMIN_TOKEN:?definir TGMON_ADMIN_TOKEN}
      - TGMON_VIEWER_TOKEN=${TGMON_VIEWER_TOKEN:-}
      - GEMINI_API_KEY=${GEMINI_API_KEY:-}
    networks:
      - homelab

networks:
  homelab:
    external: true
EOF
    echo "[+] docker-compose.yml generado."
else
    echo "[+] docker-compose.yml existente conservado."
fi

# 6. Reiniciar contenedor con Docker Compose
echo "[+] Reiniciando contenedor tgmon con Docker Compose..."
docker compose down || true
docker compose up -d --build

echo "[+] Esperando 3 segundos a que uvicorn inicialice..."
sleep 3

# 7. Verificación automatizada sin imprimir secretos en consola ni en lista de procesos
echo "=========================================================="
echo "          VERIFICACIÓN AUTOMATIZADA DE ACCESO             "
echo "=========================================================="

echo "[-] Estado de variables en el contenedor (sin exponer valores):"
docker compose exec -T tgmon python3 -c "
import os
admin_set = bool(os.getenv('TGMON_ADMIN_TOKEN') or os.getenv('TGMON_TOKEN'))
viewer_set = bool(os.getenv('TGMON_VIEWER_TOKEN'))
print(f'    TGMON_ADMIN_TOKEN configurado: {admin_set}')
print(f'    TGMON_VIEWER_TOKEN configurado: {viewer_set}')
" || true

echo ""
echo "[-] Test 1: Verificar acceso con rol ADMIN (/auth/verify):"
ADMIN_RES=$(printf 'header = "Authorization: Bearer %s"\n' "$ADMIN_TOKEN" | curl -s -o /dev/null -w "%{http_code}" -K - http://localhost:8000/auth/verify || echo "ERR")
if [ "$ADMIN_RES" = "200" ]; then
    echo "    >>> OK: HTTP 200 (Rol Admin verificado correctamente)."
else
    echo "    >>> ERROR: HTTP $ADMIN_RES al verificar rol Admin."
fi

if [ "$VIEWER_ROLE_ENABLED" -eq 1 ]; then
    echo ""
    echo "[-] Test 2: Verificar acceso con rol VIEWER (/auth/verify):"
    VIEWER_RES=$(printf 'header = "Authorization: Bearer %s"\n' "$VIEWER_TOKEN" | curl -s -o /dev/null -w "%{http_code}" -K - http://localhost:8000/auth/verify || echo "ERR")
    if [ "$VIEWER_RES" = "200" ]; then
        echo "    >>> OK: HTTP 200 (Rol Viewer verificado correctamente)."
    else
        echo "    >>> ERROR: HTTP $VIEWER_RES al verificar rol Viewer."
    fi

    echo ""
    echo "[-] Test 3: Verificar bloqueo de mutación para rol VIEWER (debe devolver HTTP 403):"
    FORBIDDEN_RES=$(printf 'header = "Authorization: Bearer %s"\n' "$VIEWER_TOKEN" | curl -s -o /dev/null -w "%{http_code}" -X POST -K - http://localhost:8000/docker/containers/tgmon/restart || echo "ERR")
    if [ "$FORBIDDEN_RES" = "403" ]; then
        echo "    >>> OK: HTTP 403 Forbidden (El rol Viewer no puede ejecutar mutaciones)."
    else
        echo "    >>> Estado obtenido: HTTP $FORBIDDEN_RES"
    fi
else
    echo ""
    echo "[-] Test 2/3: Rol VIEWER deshabilitado; se omiten pruebas de credencial viewer."
fi

unset ADMIN_TOKEN VIEWER_TOKEN EXISTING_GEMINI_KEY

echo "=========================================================="
echo " [TGMon v2] Actualización y verificación finalizadas      "
echo "=========================================================="
