#!/bin/bash
echo -e "\n🔍 VERIFICANDO CONFIGURACIÓN DE AUTO-ARRANQUE...\n"

# 1. Estado del servicio de Docker
echo "1️⃣  Demonio de Docker (Systemd):"
if systemctl is-enabled --quiet docker; then 
    echo "  ✅ Habilitado (arranca con el SO)"
else 
    echo "  ❌ DESHABILITADO (ejecutá: sudo systemctl enable docker)"
fi

# 2. Dependencia del disco HDD
echo -e "\n2️⃣  Orden de booteo (Docker espera al HDD):"
if systemctl show docker --property=Requires,After | grep -i -q "storage_hdd"; then
    echo "  ✅ Override activo (Docker espera a que monte el HDD antes de arrancar)"
else
    echo "  ⚠️ Override NO detectado o el nombre del mount es distinto. Revisá 'systemctl status docker'"
fi

# 3. Política de contenedores
echo -e "\n3️⃣  Política de reinicio de los contenedores:"
# Buscamos los que NO tengan unless-stopped o always
BAD_POLICY=$(docker inspect -f '{{.Name}} | {{.HostConfig.RestartPolicy.Name}}' $(docker ps -a -q) | grep -v -E "unless-stopped|always")

if [ -z "$BAD_POLICY" ]; then
    echo "  ✅ Todos los contenedores tienen 'unless-stopped' (van a levantar solos)"
else
    echo "  ❌ Estos contenedores NO tienen la política correcta y van a quedar apagados:"
    echo "$BAD_POLICY" | column -t -s '|' | sed 's/^/     /'
fi
echo ""
