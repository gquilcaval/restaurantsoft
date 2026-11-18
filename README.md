# RestOS

Sistema para restaurantes: carta por QR para el comensal, app del mesero, cocina, caja, asistencia del personal y comandas impresas.

- **frontend/** — Angular (se sirve con nginx en el puerto 8081)
- **backend/** — Spring Boot + PostgreSQL (puerto 8080), migraciones con Flyway
- **compose.yaml** — levanta todo con Docker

## Levantarlo en una computadora nueva

Solo necesitas **Git** y **Docker Desktop** (no hace falta instalar Java ni Node).

```bash
git clone <enlace-del-repositorio> restos
cd restos
cp .env.example .env
docker compose up -d --build
```

La primera vez tarda varios minutos. Luego abre http://localhost:8081

Usuarios de demo (contraseña `RestOS123!`): `admin@restos.local`, `mesero@restos.local`, `cocina@restos.local`, `platform@restos.local`.

### Probar desde el celular

En `.env` cambia la IP de `PUBLIC_BASE_URL` y `CORS_ALLOWED_ORIGINS` por la IP de **esta** computadora en tu red
(Mac: Ajustes → Wi-Fi → Detalles; Windows: `ipconfig`) y vuelve a ejecutar `docker compose up -d`.
El celular debe estar en la misma red Wi-Fi. En Windows, permite el acceso cuando el firewall lo pregunte.

### Llevar los datos de otra computadora

En la computadora que tiene los datos:

```bash
./scripts/exportar-datos.sh
```

Copia el archivo que indica (queda en `../respaldos`, fuera del proyecto) y en la otra computadora:

```bash
./scripts/importar-datos.sh ruta/al/restos-datos-XXXX.tar.gz
```

Ese archivo tiene datos reales: **no lo subas a GitHub**. En Windows ejecuta los scripts desde Git Bash.

## Después de cambiar código

```bash
docker compose up -d --build
```

## Trabajar en dos computadoras

Antes de empezar: `git pull`. Al terminar: `git add -A && git commit -m "..." && git push`.
