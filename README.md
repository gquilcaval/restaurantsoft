# RestOS

Sistema para restaurantes: carta por QR para el comensal, app del mesero, cocina, caja, asistencia del personal y comandas impresas.

| Carpeta | Qué es |
|---|---|
| `frontend/` | Angular. Se abre en http://localhost:8081 |
| `backend/` | Spring Boot + PostgreSQL (puerto 8080). La base se crea sola con Flyway |
| `compose.yaml` | Levanta todo con Docker |
| `scripts/` | Llevar los datos de una computadora a otra |

---

## Instalar en una computadora nueva

No hace falta instalar Java, Node ni PostgreSQL: todo corre dentro de Docker.

### 1. Instalar los programas (una sola vez)

| Programa | Mac | Windows |
|---|---|---|
| **Git** | Viene con la Mac. Si al usar `git` pide instalar "herramientas de desarrollo", acepta. | Descarga de https://git-scm.com/download/win y deja todas las opciones por defecto. Instala también **Git Bash**. |
| **Docker Desktop** | https://www.docker.com/products/docker-desktop (elige Apple Silicon o Intel según tu Mac) | https://www.docker.com/products/docker-desktop. Si pide activar **WSL 2**, acepta y reinicia. |

Después de instalar Docker Desktop **ábrelo y espera a que diga "Engine running"** (ballena quieta en la barra). Debe estar abierto siempre que uses el sistema.

### 2. Crear un token de GitHub (el repositorio es privado)

GitHub no acepta tu contraseña normal desde la terminal; pide un **token**:

1. Entra a https://github.com/settings/tokens/new
2. *Note*: `RestOS otra PC` · *Expiration*: la que quieras · marca **`repo`**
3. **Generate token** y copia el texto que empieza con `ghp_` (solo se muestra una vez).

### 3. Descargar el proyecto

Abre la **Terminal** (Mac) o **Git Bash** (Windows) y ejecuta:

```bash
git clone https://github.com/gquilcaval/restaurantsoft.git restos
```

- *Username*: tu usuario de GitHub
- *Password*: pega el **token** (no se ve nada al pegar, es normal) y Enter

Se crea la carpeta `restos` dentro de la carpeta donde estabas (en Windows normalmente `C:\Users\TuUsuario\restos`).

### 4. Crear la configuración

```bash
cd restos
cp .env.example .env
```

Ahora abre el archivo `.env` con cualquier editor de texto y cambia **`192.168.1.215` por la IP de esta computadora** (aparece dos veces: en `CORS_ALLOWED_ORIGINS` y en `PUBLIC_BASE_URL`). Es la dirección que usan los celulares y la que llevan los códigos QR.

Cómo saber tu IP:
- **Mac:** Ajustes del Sistema → Wi-Fi → Detalles (junto a tu red) → *Dirección IP*
- **Windows:** en Git Bash escribe `ipconfig` y busca *Dirección IPv4* del adaptador Wi-Fi o Ethernet

> Si solo vas a probar en esa misma computadora (sin celular), puedes dejar el `.env` como está.

### 5. Levantar el sistema

```bash
docker compose up -d --build
```

La **primera vez tarda entre 5 y 15 minutos** (descarga y compila todo). Las siguientes veces es casi inmediato.
Cuando termine, espera unos 30 segundos y abre **http://localhost:8081**

### 6. Entrar

El sistema arranca con un restaurante de demostración. Contraseña para todos: **`RestOS123!`**

| Usuario | Rol |
|---|---|
| `admin@restos.local` | Dueño / administrador |
| `mesero@restos.local` | Mesero |
| `cocina@restos.local` | Cocina |
| `platform@restos.local` | Panel de plataforma (todos los restaurantes) |

Si prefieres tener **tus datos reales** (mesas, carta, personal, fotos) en vez de la demo, sigue la sección [Llevar los datos](#llevar-los-datos-de-una-computadora-a-otra).

### 7. Probar desde el celular

1. El celular debe estar en la **misma red Wi-Fi** que la computadora.
2. Hiciste el paso 4 con la IP correcta.
3. En el celular abre `http://TU-IP:8081` (por ejemplo `http://192.168.1.50:8081`).
4. **Windows:** la primera vez el Firewall pregunta si permite el acceso a Docker: marca **Redes privadas** y acepta.

Si cambias el `.env` después, aplica el cambio con `docker compose up -d`.

---

## Llevar los datos de una computadora a otra

**En la computadora que tiene los datos** (con el sistema levantado):

```bash
./scripts/exportar-datos.sh
```

Crea un archivo `restos-datos-FECHA.tar.gz` en la carpeta `respaldos`, **al lado** de la carpeta del proyecto (no dentro).

Pásalo a la otra computadora por USB, Google Drive, AirDrop, etc. **Nunca lo subas a GitHub**: tiene datos reales.

**En la otra computadora** (desde la carpeta `restos`, en Windows con Git Bash):

```bash
./scripts/importar-datos.sh ~/Downloads/restos-datos-FECHA.tar.gz
```

Pregunta si quieres reemplazar los datos: escribe `s` y Enter. **Borra los datos que tenía esa computadora** y pone los del archivo, incluidas las fotos de la carta. Después entras con los usuarios reales de ese restaurante.

---

## Uso diario

| Quiero... | Comando |
|---|---|
| Encender el sistema | Abrir Docker Desktop (arranca solo) o `docker compose up -d` |
| Apagarlo | `docker compose stop` |
| Aplicar cambios de código | `docker compose up -d --build` |
| Ver errores del servidor | `docker compose logs -f backend` |

Los datos se guardan aunque apagues la computadora o Docker.

## Trabajar en las dos computadoras

Siempre en este orden, para no mezclar cambios:

1. **Al empezar:** `git pull` (trae lo último de GitHub) y `docker compose up -d --build`
2. Trabajas.
3. **Al terminar:** `git add -A && git commit -m "qué cambié" && git push`

Solo viaja el **código**. Los datos (pedidos, mesas, personal) de cada computadora son independientes; para copiarlos usa los scripts de arriba.

Con **Claude Code**: abre Claude Code dentro de la carpeta `restos`. Lee el archivo `CLAUDE.md`, que tiene el contexto del proyecto, y puede hacer el `pull` / `push` por ti si se lo pides.

---

## Problemas comunes

| Mensaje / síntoma | Solución |
|---|---|
| `docker: command not found` o `Cannot connect to the Docker daemon` | Docker Desktop no está abierto. Ábrelo y espera a "Engine running". |
| `Authentication failed` al clonar o hacer push | Usaste la contraseña en vez del token, o el token no tiene marcado `repo`. Crea otro (paso 2). |
| `RPC failed; HTTP 400` al hacer push | Ejecuta una vez `git config http.postBuffer 157286400` y repite el push. |
| `port is already allocated` / `address already in use` | Otro programa usa el puerto 8080 u 8081. Ciérralo, o cambia el número de la izquierda en `compose.yaml` (`"8091:80"`). |
| La página abre pero no deja iniciar sesión | Espera 30 segundos más (el servidor aún arranca) y mira `docker compose logs backend`. |
| El celular no abre la página | Misma Wi-Fi, IP correcta en `.env`, `docker compose up -d` después de cambiarla y, en Windows, permiso del firewall. |
| Los QR llevan a otra dirección | `PUBLIC_BASE_URL` del `.env` tiene otra IP. Corrígela y `docker compose up -d`. |
| `./scripts/...: Permission denied` | `chmod +x scripts/*.sh` y repite. |
| `$'\r': command not found` al correr un script en Windows | `git config core.autocrlf false`, luego `git rm -r --cached . && git reset --hard` y repite. |
