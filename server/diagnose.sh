#!/bin/bash
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
for pkg in fastapi uvicorn psutil docker python-dotenv websockets; do
    version=$(pip show $pkg 2>/dev/null | grep Version | awk '{print $2}')
    if [ -n "$version" ]; then
        echo "  ✅ $pkg $version"
    else
        echo "  ❌ $pkg NOT INSTALLED"
    fi
done
echo ""

# 4. Check .env.api
echo "[4/10] Token configuration..."
if [ -f ".env.api" ]; then
    # SECURITY: never log full token; show only length + first 4 chars redacted
    TOKEN=$(grep TGMON_TOKEN .env.api | cut -d'=' -f2 | tr -d '[:space:]')
    echo "  ✅ .env.api found"
    if [ -z "$TOKEN" ]; then
        echo "  ❌ Token is EMPTY"
    else
        # Redacted: show length and prefix only (e.g., abcd****)
        _prefix=$(echo "$TOKEN" | cut -c1-4)
        echo "  ✅ Token set (length ${#TOKEN}, prefix ${_prefix}****, redacted for security)"
        unset _prefix
    fi
    unset TOKEN
else
    echo "  ❌ .env.api NOT FOUND"
fi
echo ""

# 5. Check Python files
echo "[5/10] Server files..."
for f in main.py auth.py db.py docker_api.py; do
    if [ -f "$f" ]; then
        # Check for syntax errors
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
if [ -f "metrics.db" ]; then
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

# 9. Try starting server briefly
echo "[9/10] Testing server startup..."
timeout 5 python3 -c "
import sys
sys.path.insert(0, '.')
try:
    from auth import TOKEN
    print(f'  ✅ Auth module loaded, token length: {len(TOKEN)}')
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
echo "  Quick Fix Commands:"
echo "=========================================="
echo ""
echo "  # Kill old server:"
echo "  kill \$(lsof -t -i:8000) 2>/dev/null"
echo ""
echo "  # Fix DB schema (if needed):"
echo "  sqlite3 metrics.db \"ALTER TABLE metrics ADD COLUMN memory_percent REAL;\" 2>/dev/null"
echo "  sqlite3 metrics.db \"ALTER TABLE metrics ADD COLUMN disk_total_gb REAL;\" 2>/dev/null"
echo ""
echo "  # Start server:"
echo "  source venv/bin/activate"
echo "  uvicorn main:app --host 0.0.0.0 --port 8000"
echo ""
echo "  # Test:"
echo "  curl http://localhost:8000/health -H \"Authorization: Bearer TU_TOKEN\""
echo ""
