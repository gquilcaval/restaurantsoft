#!/usr/bin/env bash
# Reemplaza los datos de esta computadora con los de un archivo creado por exportar-datos.sh.
# Uso:  ./scripts/importar-datos.sh ruta/al/restos-datos-XXXX.tar.gz
set -euo pipefail
archivo="${1:?Indica el archivo .tar.gz exportado}"
archivo="$(cd "$(dirname "$archivo")" && pwd)/$(basename "$archivo")"
cd "$(dirname "$0")/.."

read -r -p "Esto BORRA los datos actuales de esta computadora y pone los del archivo. ¿Seguir? (s/n) " ok
[ "$ok" = "s" ] || { echo "Cancelado."; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
tar -xzf "$archivo" -C "$tmp"

docker compose up -d database backend
docker compose stop backend
echo "Importando base de datos..."
docker compose exec -T database psql -q -U restos -d restos -v ON_ERROR_STOP=1 < "$tmp/base.sql" > /dev/null
docker compose start backend
echo "Importando fotos..."
sleep 3
docker compose exec -T backend sh -c 'rm -rf /app/uploads/* && tar -xzf - -C /app/uploads' < "$tmp/fotos.tar.gz"
docker compose up -d
echo "Listo. Abre http://localhost:8081"
