# ADR 0007: Vías cerradas

- Estado: aceptada
- Fecha: 2026-10-21

## Contexto

Una vía que se cierra durante el turno (un accidente, una emergencia, una obra) cambia los caminos más cortos entre los 808 lugares del plan de Suba: la base, los 806 contenedores y la portería. Recalcular la matriz completa son 808 Dijkstra, unos 88 ms con 16 hilos. Para replanificar en vivo hay que saber qué celdas cambiaron, qué camión iba por esa vía y qué hacer con los contenedores que quedan sin acceso.

R05 (Decreto 1077 de 2015, art. 2.3.2.2.2.3.34) pide avisar con tres días cualquier cambio de rutas, salvo caso fortuito o fuerza mayor.

## Decisión

1. **Reparar la matriz, no recalcularla** (`TravelMatrix.closing`). Un arco cerrado `u → v` solo cambia la celda `(i, j)` si `d(i, u) + arco + d(v, j) = d(i, j)`. Para saberlo bastan dos Dijkstra por arco: uno hacia `u` sobre el grafo invertido y otro desde `v`. Las celdas tocadas se cubren de forma voraz con filas (Dijkstra desde el punto) y columnas (Dijkstra hacia el punto). Cerrar la calle de un contenedor cambia casi toda su columna, que sale con una sola búsqueda. Con solo filas habría que recalcular cientos.
2. **Un solo valor posible por celda.** Entre caminos igual de cortos en metros, `ShortestPaths` toma el más rápido. Así una búsqueda hacia atrás da exactamente lo mismo que una hacia adelante, y la matriz reparada es idéntica a la calculada de cero. Exige arcos de al menos 1 m, que `RoadGraphBuilder` garantiza y `RoadGraph` valida.
3. **Red por tramo** (`LivePlan`). Cada tramo usa la red vigente cuando el camión arranca. Si la vía se cierra con el camión en camino y lo que le falta del camino pasa por ella, termina el arco en que va y sigue por la red nueva. Un camión que quedó dentro de una zona aislada sale por donde entró.
4. **Contenedores aislados.** Las visitas pendientes a contenedores que quedan sin camino de ida o de vuelta salen del plan, y se informan. Se rechaza un cierre que aísle la base o la portería.
5. **Reordenar lo que falta, solo en los camiones afectados.** Se midieron dos políticas: solo desviar (mismo orden de paradas) y desviar y además reordenar las paradas que faltan de cada viaje, con la búsqueda local del solver sobre la red nueva. Los camiones que el cierre no cambia conservan su orden.

Medido con 500 cierres (`mvn -pl rutea-tools -am compile exec:java@closures`, semilla 1, i5-14400F, 16 hilos, Java 21.0.12; CSV en `docs/benchmarks/f5-closures/`). Cada cierre cae en una calle que le falta recorrer a un compactador en servicio, porque un cierre al azar casi nunca toca una ruta:

| Medida | P50 | P95 | P99 | Máx. |
|---|---:|---:|---:|---:|
| Reparar la matriz | 9,2 ms | 23,9 ms | 41,7 ms | 52,2 ms |
| Matriz completa de cero | 87,8 ms | 98,3 ms | 106,0 ms | 120,9 ms |
| Filas + columnas recalculadas, de 808 | 18 | 151 | 302 | 383 |

| Política | Latencia P50 / P99 | km agregados (total) | Retraso del camión más afectado P95 / P99 | Cierres con un camión fuera de turno |
|---|---:|---:|---:|---:|
| Solo desviar | 12,5 / 45,4 ms | 370,1 | 6,3 / 12,9 min | 17 |
| Desviar y reordenar | 13,1 / 46,3 ms | 294,1 | 5,9 / 10,2 min | 15 |

En los 500 cierres, la matriz reparada es idéntica, celda por celda, a la calculada de cero. Reordenar agrega un 20,5 % menos de km y cuesta 0,6 ms más de mediana, así que es la política por defecto. En solo 1 de los 500 cierres la búsqueda local cambió un camión que el cierre no había tocado: el ahorro viene del cierre, no de un plan que tuviera margen.

## Consecuencias

- Una vía arterial cambia caminos de muchos pares: en el peor caso se recalcularon 383 filas y columnas, y aun así la reparación tardó la mitad que la matriz completa.
- El desempate cambió los segundos del 6,3 % de las celdas de la matriz de Suba, sin cambiar los metros: el solver da las mismas rutas, pero los horarios se mueven unos segundos. Se volvieron a medir las averías, los desbordes y la línea base de F4 (ver sus ADR y el roadmap).
- 57 de los 500 cierres dejan contenedores sin acceso. Sus visitas salen del plan de hoy, y recogerlos cuando se reabra la vía queda pendiente.
- En 15 de los 500 cierres, un camión termina después del turno. Rutea lo informa, pero todavía no pasa sus últimas paradas a una reserva.
- Las rutas de reserva que ya salieron, por una avería o un desborde, no se reprograman con cierres posteriores.
- Los cierres van en orden: no se acepta uno anterior al último. Desbordes y averías sí pueden llegar en cualquier orden, porque cada tramo usa la red de su hora.
- R05: el evento cita el cambio de ruta como caso fortuito. Un cierre programado debería avisarse con tres días; distinguirlos queda `PENDIENTE_VERIFICAR` (docs/normativa.md, interpretaciones).
