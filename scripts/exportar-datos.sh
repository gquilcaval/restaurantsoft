#!/usr/bin/env bash
# Guarda la base de datos y las fotos subidas en un solo archivo para llevarlo a otra computadora.
# Uso (desde la carpeta del proyecto, con el sistema levantado):  ./scripts/exportar-datos.sh
# El archivo queda FUERA del proyecto (../respaldos) porque tiene datos reales: no lo subas a GitHub.
set -euo pipefail
cd "$(dirname "$0")/.."
destino="../respaldos"
mkdir -p "$destino"
nombre="restos-datos-$(date +%Y%m%d-%H%M)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Exportando base de datos..."
docker compose exec -T database pg_dump -U restos -d restos --clean --if-exists --no-owner > "$tmp/base.sql"
echo "Exportando fotos..."
docker compose exec -T backend tar -czf - -C /app/uploads . > "$tmp/fotos.tar.gz"

tar -czf "$destino/$nombre.tar.gz" -C "$tmp" base.sql fotos.tar.gz
echo "Listo: $(cd "$destino" && pwd)/$nombre.tar.gz"
echo "Cópialo a la otra computadora (USB, Drive, AirDrop) y ahí ejecuta: ./scripts/importar-datos.sh <archivo>"
