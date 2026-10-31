# ADR 0015: Capas dentro de rutea-app y las herramientas en su propio módulo

- Estado: aceptada
- Fecha: 2026-10-31

## Contexto

`rutea-app` había crecido por fases y mezclaba tres cosas en los mismos paquetes:

- **`web` hacía de todo.** Ahí vivían 18 clases que no son HTTP: `OperationsService`, `LiveSession`, `Telemetry` y los mensajes de la API. El controlador armaba las sesiones en vivo, y el servicio y `EventsHub` se llamaban entre sí.
- **Un ciclo entre paquetes.** `scenario` importaba `planning` (`Sectors` armaba los horarios del plan; `DoorToDoor` leía la semilla de `AreaPlanner`), y `planning` importaba `scenario`.
- **23 programas de línea de comandos** (extractores, exportadores de instancias y las mediciones de cada fase) junto al código que sirve la API. Entraban al jar de la app sin que esta los usara nunca.

## Decisión

1. **Capas en `rutea-app`**, cada una importa solo las de abajo:

   | Capa | Paquete | Qué hace | Spring |
   |---|---|---|---|
   | Adaptadores | `web` | REST, WebSocket, errores RFC 9457 | sí |
   | Aplicación | `application` | casos de uso (`OperationsService`), sesiones en vivo y tipos de la API | sí |
   | Modelo | `operations` → `planning` → `scenario` | replanificar, planificar, armar el escenario | no |
   | Puerto | `persistence` | `OperationLog`, en memoria o PostgreSQL | sí |

   `web` también lee `planning.DemoPlan`, el plan que la API devuelve tal cual.
2. **Eventos en vez de llamadas cruzadas.** La aplicación publica `LiveUpdate` con el `ApplicationEventPublisher` de Spring, y `EventsHub` lo escucha y lo transmite. La aplicación no sabe que existe un WebSocket.
3. **`LayersTest` vigila las capas.** Lee los imports del código y falla si una capa importa una de arriba, o si el modelo importa Spring. No agrega dependencias (ni ArchUnit).
4. **`rutea-tools`, un módulo aparte** para los 23 programas, con un `exec:java@<id>` por herramienta. Depende de `rutea-app`; la app no sabe que existe.

## Consecuencias

- La app ya no carga las herramientas. El jar de Spring Boot sale con clasificador (`rutea-app-exec.jar`) para que `rutea-tools` compile contra las clases y no contra el jar ejecutable.
- Los comandos pasan de `mvn -pl rutea-app exec:java@x` a `mvn -pl rutea-tools -am compile exec:java@x`, que compila lo que haga falta en vez de usar lo que haya en `~/.m2`. Con `-am`, Maven corre `exec:java@x` en cada módulo de arriba. Por eso el padre salta `exec` por defecto y solo lo ejecuta el módulo que declara esa ejecución. Eso arregla también `rutea-bench`, que con `-am` fallaba en el padre por falta de `mainClass`.
- `pnpm demo:data` usa `exec:java@demo-plan` y ya no arma el jar completo para correr una clase.
- Para las herramientas se hicieron públicos miembros que eran del paquete: `LivePlan` (clase, `of`, `legs`, `arcs`, `truckCount` y `Leg.arriveSeconds`), `BreakdownReplanner.subInstance` y `toOriginalNodes`, y `OperatorContainers.parse` y `Layer.fields`.
- Comprobado: `mvn verify` pasa en los ocho módulos. `exec:java@side-loading` desde `rutea-tools` escribe un CSV idéntico al de la corrida del 2026-10-29.
