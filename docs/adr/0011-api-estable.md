# ADR 0011: Una API estable: errores RFC 9457, turnos en el WebSocket y un contrato que se prueba

- Estado: aceptada
- Fecha: 2026-10-22

## Contexto

F6 pide una API REST y WebSocket estables. Probada en sus bordes con los datos reales de Suba, la API de F5 tenía cuatro fallas:

- **Dos formatos de error.** Los 400 propios traían `{"error": "<mensaje claro>"}`. Un JSON mal formado, un campo faltante, un 404 o un 405 traían el de Spring, `{"error": "Bad Request", ...}`, y el mapa mostraba "Bad Request".
- **Cierres sin límite.** Un cierre en `lon 0, lat 0` (golfo de Guinea) se aceptaba y cerraba una vía secundaria de Usme.
- **Velocidad sin tope.** Con `speed: 1e9`, el turno entero pasaba en un solo reporte y los sensores despachaban 47 desbordes de golpe.
- **WebSocket sin resincronizar.** Un cliente que perdía la conexión perdía los eventos de ese rato, y no se enteraba si el servidor arrancaba un turno distinto. Pedir el historial completo para ponerse al día pesaba 1,76 MB con 81 eventos.

## Decisión

1. **Errores en RFC 9457** (`application/problem+json`), también los que arma Spring. `detail` va en español y dice qué se esperaba: el campo que falta, los métodos que admite la ruta, a cuántos metros de una calle quedó el punto.
2. **Validación en el borde.** Un cierre se marca a menos de 150 m de una calle del grafo. El reloj va hasta 3.600×: así un reporte de 250 ms cubre como mucho 15 min de turno.
3. **Turnos en el WebSocket.** Al conectarse, y cada vez que empieza un turno, el cliente recibe `{"type": "shift", "shift", "seq"}`. `GET /api/events?after=N` devuelve solo lo que falta, envuelto en `{shift, events}` para que nunca se mezclen eventos de dos turnos. El mensaje `reset` desaparece: un turno nuevo es un `shift` con otro id.
4. **Ids de turno únicos también sin base.** En memoria, los turnos se numeran desde la hora de arranque, así que un cliente conectado nota el reinicio. Con PostgreSQL, el reinicio retoma el mismo turno (ADR 0010) y el cliente no tiene nada que hacer.
5. **Clientes lentos fuera.** Una sesión que tarda más de 5 s en recibir, o acumula más de 1 MiB, se cierra en vez de frenar la telemetría de todos. Al volver se pone al día con el punto 3.
6. **El contrato es `docs/api.md`.** `ApiContractTest` compara sus listas de campos y valores con los records y enums del código. Si un campo cambia en el código y no en el documento, el test falla.

## Consecuencias

- Medido sobre la app con los datos de Suba: los 404, 405, 415 y los JSON mal formados salen todos en `application/problem+json` y en español. Un cliente conectado recibe `shift` con `seq` 33. Al reproducir con el turno terminado recibe el turno nuevo con `seq` 0.
- Ponerse al día cuesta lo que falta y no el historial completo, más las instantáneas de telemetría (≈100 KB) y sensores (≈19 KB). Las instantáneas solo se piden al reconectarse o al cambiar de turno.
- Rompe la API anterior a propósito: `GET /api/events` deja de ser una lista, el mensaje `reset` desaparece y los errores cambian de forma. El único cliente es `web/` y se actualizó en el mismo cambio.
- No se probó un cliente que deja de leer hasta llenar el búfer: el límite de 1 MiB es del decorador de Spring, no una medición.
- Sin versión en la ruta (`/api/v1`): con un solo cliente en el mismo repo, el contrato probado protege más que una versión. Se agrega el día que haya clientes que no se despliegan junto con la app.
