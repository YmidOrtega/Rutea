# ADR 0010: Un comando para levantar todo, y persistencia por órdenes

- Estado: aceptada
- Fecha: 2026-10-21

## Contexto

F6 cierra cuando alguien clona el repo, ejecuta un comando y ve la zona piloto funcionando. La app necesita el grafo vial y los sitios sensibles de Suba, que salen de un extracto de OpenStreetMap de 333 MB que Geofabrik guarda por un tiempo limitado (ADR 0004). El roadmap también pide persistencia en PostgreSQL + PostGIS, y R08 pide monitorear la posición de los vehículos y sus tiempos y movimientos.

## Decisión

1. **`docker compose up`** levanta tres servicios:
   - `data` descarga el grafo y los sitios del release [`data-osm-suba-20261019`](https://github.com/YmidOrtega/Rutea/releases/tag/data-osm-suba-20261019) (2 MB) y los verifica contra `data/osm/SHA256SUMS`, que está en el repo; no confía en las sumas que vienen con la descarga. Si ya están y verifican, no descarga nada.
   - `db` es PostGIS 17 y no publica su puerto.
   - `app` es una sola imagen: Spring sirve el mapa compilado en el mismo puerto que la API.
2. **Datos reproducibles, no versionados en git.** Reconstruir el grafo y los sitios desde el extracto da los mismos bytes (comprobado): el release es un atajo verificable, no una fuente aparte.
3. **Persistencia por órdenes.** Se guarda cada orden del turno (avería, desborde reportado o detectado por un sensor, cierre de vía) con su hora, no el estado. Al arrancar, la app repite las órdenes del último turno y queda igual: la operación es determinista (semillas fijas, matriz con un solo valor por celda). El reloj vuelve, pausado, a la hora del último punto transmitido. Si una orden ya no se puede repetir porque cambiaron los datos o el código, empieza un turno nuevo y lo dice el log.
4. **Posiciones en PostGIS (R08).** Cada punto que transmite un vehículo se guarda como `geometry(Point, 4326)` con su hora del turno. Kilómetros, tiempos detenidos y pasos cerca de un sitio salen con SQL espacial (consultas de ejemplo en `docs/arquitectura.md`).
5. **Sin base, en memoria.** Sin el perfil `postgres` la app funciona como antes; así corren los tests y `pnpm dev`.

## Actualización (2026-10-29)

`data` descarga también el release [`data-bogota-20261029`](https://github.com/YmidOrtega/Rutea/releases/tag/data-bogota-20261029) (F7): el grafo de Bogotá, sus sitios sensibles y los contenedores de las ASE 1, 2 y 4. Lo verifica contra `data/bogota/SHA256SUMS`. `fetch-data.sh` recibe pares de sumas y release y revisa archivo por archivo. Los contenedores no salen de OSM y no se pueden reconstruir: son una copia fechada de capas que cambian cada mes y no declaran licencia (`PENDIENTE_VERIFICAR`).

## Consecuencias

- Medido: después de reiniciar la app con 52 órdenes guardadas, los 53 eventos son idénticos a los de antes (sin contar las latencias medidas) y la repetición tarda 433 ms. Un turno a 240× hasta las 18:57 guardó 10.720 puntos.
- La imagen pesa 446 MB, casi todo de la JRE. Una JRE recortada con `jlink` la bajaría; queda pendiente.
- Las rutas de los eventos se recalculan al repetir, no se guardan: si cambia el solver, un turno guardado puede dejar de repetirse y empieza uno nuevo.
- La velocidad del reloj no se guarda: al retomar vuelve a 60×.
- La contraseña de la base de Compose es `rutea`: la base no sale de la red de Compose. Para algo expuesto, va por variables de entorno (`RUTEA_DB_PASSWORD`).
