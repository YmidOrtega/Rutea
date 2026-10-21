# ADR 0005: Replanificación ante averías con arranque en caliente

- Estado: aceptada
- Fecha: 2026-10-20

## Contexto

F5 cierra cuando una avería produce un plan nuevo con su latencia P99 medida. La norma (R05, Decreto 1077 de 2015, art. 2.3.2.2.2.3.34) pide restablecer el servicio en máximo tres horas.

A la hora de la avería, cada camión del plan ya hizo parte de su turno. El camión averiado deja contenedores sin recoger. Los demás llevan carga y tienen el turno casi lleno: el plan de menos camiones los deja con poco margen. Reoptimizar todo desde cero tardaría y movería rutas que los conductores ya están haciendo.

## Decisión

Se congela lo recorrido y solo se replanifica lo que dejó el camión averiado:

1. **Línea de tiempo del plan.** De la matriz y la asignación de viajes sale a qué hora llega cada camión a cada parada. Son los mismos segundos que dibuja el mapa.
2. **Contenedores huérfanos.** Son los del camión averiado que no alcanzó a terminar de recoger a la hora de la avería; si se averió a mitad del servicio, la reserva también recoge ese contenedor. Lo que ya recogió queda en el camión; remolcarlo y descargarlo no se modela.
3. **Camiones de reserva.** Salen de la base tras un tiempo de despacho y cubren los huérfanos. Los viajes salen de un CVRP sobre esos contenedores, con la misma matriz y el mismo modelo base → contenedores → portería.
4. **Arranque en caliente.** ALNS arranca desde los huérfanos en el orden que tenían en el plan, partidos por capacidad (`AlnsSolver.solveFrom`), con límite de iteraciones. Así el plan nuevo se parece al vigente y la latencia no depende de un límite de tiempo.
5. **Asignación de turnos.** `ShiftScheduler` reparte los viajes nuevos entre los camiones de reserva.

La latencia es el tiempo real desde la avería hasta tener el plan nuevo. El cumplimiento de R05 se mide hasta que un camión de reserva llega al primer contenedor huérfano.

## Consecuencias

- El solver gana `solveFrom` sin cambiar `solve`: con el arnés, los costos de `alns` en el set X son idénticos antes y después.
- Ningún camión que siga en servicio cambia de ruta. Es más fácil de comunicar en la operación, pero puede costar más km que reasignar huérfanos a camiones con margen. Esa alternativa queda medida como trabajo futuro.
- El tiempo de despacho de la reserva es un supuesto (`PENDIENTE_VERIFICAR`) hasta tener datos del operador.

## Actualización (2026-10-20): reasignar huérfanos a camiones en servicio

Se agregaron dos estrategias junto a la de solo reserva (`BreakdownReplanner.Strategy`). Las dos insertan huérfanos al menor costo en los camiones que siguen en servicio, solo en la parte del recorrido que aún no hicieron, sin pasar la capacidad del viaje ni el turno. Medidas sobre las mismas 500 averías (semilla 1, `docs/benchmarks/f5-breakdowns/breakdowns-20261020-232857-106.csv`):

| Estrategia | Reserva (total) | km agregados | Primer huérfano recogido (P50 / máx.) | R05 |
|---|---:|---:|---:|---:|
| Solo reserva | 351 | 35.919,5 | 39,9 / 73,4 min | 500/500 |
| Reasignar primero | 343 | 42.130,2 (+17,3 %) | 12,5 / 149,3 min | 500/500 |
| Reasignar solo si evita la reserva | 343 | 35.456,1 (−1,3 %) | 39,9 / 149,3 min | 500/500 |

Con el plan de menos camiones, los que siguen en servicio tienen poco margen: absorben 7 huérfanos de mediana y evitan la reserva en 8 de 500 averías. "Reasignar solo si evita la reserva" nunca empeora los km frente a "solo reserva". "Reasignar primero" acelera la respuesta a costa de km. Es una decisión operativa, así que las tres quedan disponibles; la API en vivo usa "solo reserva", que no cambia la ruta de ningún camión en servicio.
