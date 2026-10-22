# API de Rutea

El contrato entre `rutea-app` y quien la use: el mapa de `web/` o cualquier otro cliente. JSON en `/api`, y un WebSocket en `/ws/events` para lo que pasa en vivo. [ADR 0011](adr/0011-api-estable.md) explica por qué es así.

`ApiContractTest` compara los campos y valores de este documento con los del código. Si cambia un campo y este archivo no, el test falla.

Convenciones:

- **Horas:** segundos desde el inicio del turno; el turno empieza a la hora de `meta.shiftStart` del plan (18:00).
- **Posiciones:** `[lon, lat]`, en el orden que esperan MapLibre y deck.gl.
- **Distancias:** metros enteros.
- **Eventos:** cada uno lleva un `seq` que empieza en 1 en cada turno y sube de uno en uno. Un cliente nunca cuenta dos veces el mismo.
- **Datos simulados:** las calles son reales (OSM); los contenedores, sus cargas y los sensores son simulados (`meta.simulated`).

## Errores

Todo error sale en [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) (`application/problem+json`). `detail` va en español y dice qué se esperaba:

```json
{"detail": "el punto (-74.20000, 4.75000) queda a 6807 m de la calle más cercana; un cierre se marca sobre una calle, a menos de 150 m",
 "instance": "/api/closures", "status": 400, "title": "Bad Request"}
```

| Estado | Cuándo |
|---|---|
| 400 | JSON mal formado, un campo que falta o con otro tipo, o una orden que la operación no admite (camión o contenedor que no existe, hora fuera del turno, punto lejos de una calle, velocidad fuera de 0–3.600×) |
| 404 | Ruta que no existe |
| 405 | Método que la ruta no admite; `detail` dice cuáles admite |
| 415 | Cuerpo que no viene como `application/json` |
| 503 | Faltan los datos de OSM; `detail` dice cuál falta y con qué comando se genera |

## REST

| Método y ruta | Cuerpo | Responde |
|---|---|---|
| `GET /api/plan` | | `DemoPlan` |
| `GET /api/events?after=N` | | `EventsPage`: los eventos con `seq` mayor a `N` (por defecto 0, todos) |
| `POST /api/breakdowns` | `BreakdownRequest` | Lista de eventos: la avería y los desbordes que hubo que volver a despachar |
| `POST /api/overflows` | `OverflowRequest` | `OverflowEvent` |
| `POST /api/closures` | `ClosureRequest` | `ClosureEvent` |
| `GET /api/telemetry` | | `TelemetryMessage` con todos los vehículos en la calle |
| `GET /api/sensors` | | `SensorsMessage` con todas las lecturas y los litros de cada contenedor |
| `PUT /api/clock` | `ClockRequest` | `TelemetryMessage` con todos los vehículos y el reloj nuevo |

Lo que cambia la operación (averías, desbordes, cierres, el reloj) también llega a todos los clientes por el WebSocket. Quien hizo la petición lo recibe dos veces, en la respuesta y por el WebSocket, y lo cuenta una sola vez por su `seq`.

Sin `atSeconds`, una orden ocurre a la hora del reloj compartido.

## WebSocket `/ws/events`

Solo del servidor al cliente. Cada mensaje es un objeto JSON con `type`:

| `type` | Mensaje | Cuándo |
|---|---|---|
| `shift` | `ShiftMessage` | Al conectarse y cada vez que empieza un turno nuevo |
| `telemetry` | `TelemetryMessage` | Cada 250 ms mientras corre el reloj: solo los vehículos que cambiaron |
| `sensors` | `SensorsMessage` | Cuando una lectura cruza un paso de 5 %: solo las que cambiaron |
| `breakdown`, `overflow`, `closure` | `BreakdownEvent`, `OverflowEvent`, `ClosureEvent` | Cuando ocurren |

### Ponerse al día

La telemetría y los sensores mandan solo lo que cambió, así que un cliente que pierde mensajes queda desfasado. Con `shift` se pone al día:

1. Si `shift` no es el turno que conocía, empezó otro: olvida los eventos y pide `GET /api/events`.
2. Si es el mismo y `seq` es mayor que el último que tiene, pide `GET /api/events?after=<su último seq>`.
3. Si se reconectó, o si cambió el turno, pide `GET /api/telemetry` y `GET /api/sensors` y reemplaza lo que tenía.

`EventsPage` trae su `shift`: si no coincide con el último `shift` recibido, la respuesta es de otro turno y se descarta.

Con la base de datos (perfil `postgres`), un reinicio de la app retoma el mismo turno con los mismos eventos y `seq` ([ADR 0010](adr/0010-un-comando-y-persistencia.md)), y los clientes conectados no pierden nada. Sin base, cada arranque es un turno nuevo.

Un cliente que tarda más de 5 s en recibir, o acumula más de 1 MiB sin leer, se desconecta para no frenar a los demás. Al reconectarse recibe `shift` y se pone al día.

## Peticiones

### `BreakdownRequest`

Campos: `truck`, `atSeconds`

`truck` es un compactador del plan, como `C-03`. `atSeconds` es opcional.

### `OverflowRequest`

Campos: `container`, `atSeconds`

`container` es el id de un contenedor no aprovechable, como `N0123`.

### `ClosureRequest`

Campos: `lon`, `lat`, `atSeconds`

Se cierra en los dos sentidos la calle más cercana al punto, si queda a menos de 150 m.

### `ClockRequest`

Campos: `running`, `speed`

`speed` va de 0 (sin incluir) a 3.600 veces el tiempo real. Reproducir con el turno terminado empieza un turno nuevo.

## Respuestas y mensajes

### `EventsPage`

Campos: `shift`, `events`

### `ShiftMessage`

Campos: `type`, `shift`, `seq`

`seq` es el último evento del turno; 0 si todavía no hay ninguno.

### `BreakdownEvent`

Campos: `type`, `seq`, `truck`, `atSeconds`, `dispatchSeconds`, `latencyMs`, `orphans`, `reserve`, `firstPickupSeconds`, `lastPickupSeconds`, `meetsRecoveryRule`, `citation`

`orphans` son los contenedores que el camión averiado no alcanzó a recoger. `reserve` son las rutas de reserva (`R-03-1`, `R-03-2`… para `C-03`), con las horas ya corridas a la de despacho. `meetsRecoveryRule` dice si el primer huérfano se recoge en máximo tres horas (R05). `latencyMs` es el tiempo real de replanificar.

### `OverflowEvent`

Campos: `type`, `seq`, `container`, `atSeconds`, `cause`, `pickup`, `truck`, `pickupSeconds`, `extraMeters`, `latencyMs`, `routes`, `visits`

`cause` es `reporte`, `sensor` o `avería de C-03`. `routes` trae el turno nuevo del compactador que se desvía o la ruta de la reserva; `visits`, la hora nueva de llegada a cada contenedor de ese compactador. Los dos van vacíos si nada cambia.

### `LiveOperation.Pickup`

Valores: `PLANNED`, `IN_SERVICE`, `RESERVE`

`PLANNED`: lo recoge su visita del plan. `IN_SERVICE`: se desvía el compactador en servicio que llega primero. `RESERVE`: ninguno tiene cupo y sale una reserva desde la base.

### `ClosureEvent`

Campos: `type`, `seq`, `atSeconds`, `roadClass`, `path`, `latencyMs`, `recomputed`, `matrixSize`, `changedPairs`, `isolated`, `dropped`, `changes`, `routes`, `visits`, `citation`

`path` es la geometría de la calle cerrada. `recomputed` son las filas más columnas de la matriz que se recalcularon, de `matrixSize`. `isolated` son los contenedores que quedaron sin acceso; `dropped`, los que de esos tenían visita pendiente.

### `ClosureEvent.Change`

Campos: `truck`, `extraMeters`, `extraSeconds`, `rerouted`, `late`

### `TelemetryMessage`

Campos: `type`, `clock`, `vehicles`

En el WebSocket, `vehicles` trae solo los que cambiaron desde el reporte anterior; en `GET /api/telemetry` y `PUT /api/clock`, todos los que están en la calle.

### `TelemetryMessage.Clock`

Campos: `seconds`, `speed`, `running`, `endSeconds`

### `TelemetryMessage.Report`

Campos: `id`, `stream`, `state`, `points`

`points` es lo recorrido desde el reporte anterior, como `[lon, lat, segundos]`; el último es dónde está.

### `TelemetryMessage.VehicleState`

Valores: `DRIVING`, `COLLECTING`, `UNLOADING`, `BROKEN`, `DONE`

### `SensorsMessage`

Campos: `type`, `seconds`, `readings`, `liters`

`readings` va de contenedor a porcentaje de llenado, en pasos de 5 %. `liters` solo viene en `GET /api/sensors`; en el WebSocket va vacío.

## El plan

### `DemoPlan`

Campos: `meta`, `sites`, `containers`, `routes`, `compliance`, `sectors`

`sectors` son los horarios de paso por UPZ que se publican (R04). Salen del plan y no cambian con los eventos del turno: un cambio de horario se avisa con tres días (R05).

### `DemoPlan.Meta`

Campos: `zone`, `simulated`, `network`, `solver`, `seed`, `iterations`, `shiftStart`, `shiftSeconds`, `serviceSeconds`, `unloadSeconds`, `litersPerKm`, `kgCo2PerLiter`, `attribution`, `assumptions`

`attribution` es la atribución de OSM que exige la ODbL: quien muestre el plan debe mostrarla. `assumptions` es lo que el plan supone sin dato publicado, para mostrarlo tal cual.

### `DemoPlan.Site`

Campos: `id`, `label`, `position`

### `DemoPlan.Container`

Campos: `id`, `stream`, `position`, `kg`, `route`, `visitSeconds`, `noCompaction`, `nightNoise`, `rules`, `sector`

`noCompaction` (R07) y `nightNoise` (R02) marcan las paradas cerca de un sitio sensible; `rules` las explica en texto. `sector` es el código de su UPZ, o `rural`.

### `DemoPlan.Sector`

Campos: `ref`, `name`, `outline`, `passes`

`ref` es el código oficial de la UPZ (`27` es Suba) o `rural` para lo que queda fuera de todas. `outline` es el borde simplificado a 5 m, cerrado; la zona rural no trae borde.

### `DemoPlan.Pass`

Campos: `stream`, `route`, `fromSeconds`, `toSeconds`, `containers`

Un vehículo pasando por el sector: de su primera a su última llegada planeada, redondeadas hacia afuera al cuarto de hora. Si vuelve más de 30 min después, es otra pasada.

### `DemoPlan.Route`

Campos: `id`, `stream`, `capacityKg`, `loadKg`, `distanceMeters`, `durationSeconds`, `tripLoadsKg`, `stops`, `path`, `times`

`times[i]` son los segundos desde la salida hasta `path[i]`. `tripLoadsKg[i]` es la carga del viaje `i` al relleno.

### `WasteStream`

Valores: `RECYCLABLE`, `ORGANIC`, `NON_RECYCLABLE`

Resolución 2184 de 2019: blanco, verde y negro.

### `Compliance.Check`

Campos: `id`, `citation`, `count`, `summary`, `status`

### `Compliance.Status`

Valores: `OK`, `ACTION`, `FAIL`
