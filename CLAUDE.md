# RestOS — notas para Claude

SaaS para restaurantes en Perú. El usuario habla español: responder en español, con poco texto. Prueba desde el celular en la red local.

## Cómo correrlo
- Todo corre en Docker: `docker compose build frontend|backend && docker compose up -d frontend|backend` (desde esta carpeta). No depender de Node/Java locales.
- Al compilar el backend, `BUILD SUCCESS` aparece primero en el paso go-offline; la señal real de fin es `web-backend  Built` / la imagen creada.
- Base: PostgreSQL en el servicio `database` (usuario/base `restos`). `docker compose exec database psql -U restos -d restos`.
- Datos entre computadoras: `scripts/exportar-datos.sh` / `scripts/importar-datos.sh` (el archivo queda en `../respaldos`, nunca en git).

## Reglas
- Nunca subir `.env`, dumps SQL ni `../respaldos` (tienen datos reales).
- Antes de tocar datos reales de la base, hacer un respaldo. Nunca borrar registros de auditoría (`audit_events`).
- Ediciones por script (sed/python): marcadores contiguos y revisar `git diff --stat` después.
- No hacer commit ni push si el usuario no lo pide.

## Técnico
- Frontend: Angular zoneless con signals (`@if/@for/@let`). Un campo normal no repinta la vista: usar `signal`. Presupuesto de 4 kB por SCSS de componente → partir estilos en varios archivos. Estilos del padre no llegan a hijos (encapsulación), salvo `DinerPage` (`ViewEncapsulation.None`).
- Backend: Spring Boot 4, Java 17, `JdbcClient`, Flyway (`backend/src/main/resources/db/migration`, siguiente número tras el último `V__`). Jackson 3: evitarlo en código propio. Un componente de record no puede llamarse `notify`.
- Tiempo real por STOMP. Avisos de mesa: publicar con `realtime.waiters(...)` (calcula a qué mesero le suena, `audience.alert`) o `waitersEveryone(...)` si es una escalada.
- Acciones auditables: agregar el tipo en `AuditService.ACTIONS`.

## Diseño (preferencias del usuario)
- Estilo «liquid glass»: tarjetas translúcidas, SIN bordes blancos ni líneas brillantes; sombras suaves y cortas tipo Material; en modo oscuro elevación tonal. Revisar siempre el modo oscuro.
- Formularios mínimos: solo lo indispensable, marcar qué es opcional, plegar lo opcional, preferir chips/interruptores a campos de texto, abrir como hoja inferior (celular) o diálogo. Rechazó formularios largos.
- Nada que parezca botón sin serlo. Poco texto.

## Módulos con decisiones tomadas
- **Asistencia** (adicional por restaurante, `organizations.attendance_enabled`): vive en `/personal`; la tablet marca en `/asistencia` con PIN. Horarios versionados por fecha, tolerancia solo la cambia el dueño, correcciones auditadas (últimos 31 días; el encargado no corrige las suyas).
- **Mesero a cargo** por visita (`table_sessions.waiter_id`): mesero del plano → de la zona con menos mesas en turno → quien abrió. Escalan a todos: llamado 1 min, cuenta 90 s, plato listo 2 min.
- **Comandas impresas** por estación: estación con ruta en `print_routes` imprime (sin ruta = pantalla de cocina). Ticketeras ESC/POS en red (9100) vía el programa puente `frontend/public/descargas/RestosPrintAgent.java`. Para probar sin impresora: socket falso en 9100 y dejar todo como estaba.
- Plan de lanzamiento (fases 0–6) propuesto pero NO aprobado: no avanzar sin que el usuario lo pida.
