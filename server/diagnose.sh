#!/usr/bin/env bash
# TgMon Server Diagnostic Script
# Run: bash diagnose.sh

echo "=========================================="
echo "  TgMon Server Diagnostic"
echo "=========================================="
echo ""

# 1. Check Python
echo "[1/10] Python version..."
python3 --version 2>&1 || echo "  ❌ Python3 not found"
echo ""

# 2. Check venv
echo "[2/10] Virtual environment..."
if [ -f "venv/bin/activate" ]; then
    echo "  ✅ venv exists"
else
    echo "  ❌ venv not found"
    exit 1
fi
echo ""

# 3. Activate and check packages
echo "[3/10] Checking packages..."
source venv/bin/activate
for pkg in fastapi uvicorn psutil docker python-dotenv websockets slowapi; do
    version=$(pip show "$pkg" 2>/dev/null | grep Version | awk '{print $2}')
    if [ -n "$version" ]; then
        echo "  ✅ $pkg $version"
    else
        echo "  ❌ $pkg NOT INSTALLED"
    fi
done
echo ""

# 4. Check .env.api / app/.env.api
echo "[4/10] Token configuration..."
ENV_FILE=""
if [ -f "app/.env.api" ]; then
    ENV_FILE="app/.env.api"
elif [ -f ".env.api" ]; then
    ENV_FILE=".env.api"
elif [ -f ".env" ]; then
    ENV_FILE=".env"
fi

if [ -n "$ENV_FILE" ]; then
    # SECURITY: never print token value, prefix, or length
    TOKEN=$(grep -E '^(TGMON_ADMIN_TOKEN|TGMON_TOKEN)=' "$ENV_FILE" | head -n 1 | cut -d'=' -f2- | tr -d '[:space:]')
    echo "  ✅ $ENV_FILE found"
    if [ -z "$TOKEN" ]; then
        echo "  ❌ Admin token is EMPTY"
    else
        echo "  ✅ Admin token is configured (value hidden for security)"
    fi
    unset TOKEN
else
    echo "  ❌ .env.api / .env NOT FOUND"
fi
echo ""

# 5. Check Python files
echo "[5/10] Server files..."
for f in app/main.py app/auth.py app/db.py app/docker_api.py app/ai.py app/models.py; do
    if [ -f "$f" ]; then
        python3 -c "import py_compile; py_compile.compile('$f', doraise=True)" 2>/dev/null
        if [ $? -eq 0 ]; then
            echo "  ✅ $f (syntax OK)"
        else
            echo "  ❌ $f (SYNTAX ERROR)"
        fi
    else
        echo "  ❌ $f MISSING"
    fi
done
echo ""

# 6. Check database
echo "[6/10] Database..."
if [ -f "app/metrics.db" ]; then
    echo "  ✅ app/metrics.db exists"
    echo "  Columns:"
    sqlite3 app/metrics.db ".schema metrics" 2>/dev/null | sed 's/^/    /'
elif [ -f "metrics.db" ]; then
    echo "  ✅ metrics.db exists"
    echo "  Columns:"
    sqlite3 metrics.db ".schema metrics" 2>/dev/null | sed 's/^/    /'
else
    echo "  ⚠️  metrics.db not found (will be created on startup)"
fi
echo ""

# 7. Check port
echo "[7/10] Port 8000..."
if lsof -i:8000 > /dev/null 2>&1; then
    echo "  ⚠️  Port 8000 is IN USE by:"
    lsof -i:8000 | head -3 | sed 's/^/    /'
    echo "  Kill with: kill \$(lsof -t -i:8000)"
else
    echo "  ✅ Port 8000 is free"
fi
echo ""

# 8. Check Docker
echo "[8/10] Docker..."
if docker ps > /dev/null 2>&1; then
    count=$(docker ps -q | wc -l)
    echo "  ✅ Docker accessible ($count containers running)"
else
    echo "  ⚠️  Docker not accessible (container stats will fail)"
fi
echo ""

# 9. Try starting server modules briefly
echo "[9/10] Testing server modules..."
timeout 5 python3 -c "
import sys
sys.path.insert(0, './app')
try:
    from auth import ADMIN_TOKEN, VIEWER_TOKEN
    print(f'  ✅ Auth module loaded (admin configured: {bool(ADMIN_TOKEN)}, viewer configured: {bool(VIEWER_TOKEN)})')
except Exception as e:
    print(f'  ❌ Auth module failed: {e}')

try:
    from db import init_db
    init_db()
    print('  ✅ DB initialized')
except Exception as e:
    print(f'  ❌ DB init failed: {e}')
" 2>&1 | sed 's/^/  /'
echo ""

# 10. Network
echo "[10/10] Network interfaces..."
ip -4 addr show | grep -E "inet " | sed 's/^/  /'
echo ""

echo "=========================================="
echo "  Quick Commands (from server/):"
echo "=========================================="
echo ""
echo "  # Start server locally:"
echo "  source venv/bin/activate"
echo "  uvicorn --app-dir app main:app --host 127.0.0.1 --port 8000"
echo ""
echo "  # Run tests:"
echo "  pytest tests/test_api.py -v"
echo ""
