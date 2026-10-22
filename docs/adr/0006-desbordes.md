# ADR 0006: Contenedores desbordados

- Estado: aceptada
- Fecha: 2026-10-21

## Contexto

Un contenedor desbordado (por un reporte o, más adelante, por un sensor) tiene que recogerse pronto: los residuos quedan en la calle. Hay tres formas de recogerlo: esperar su visita del plan, desviar un compactador que ya está en servicio o sacar un camión de reserva de la base. La replanificación ante averías (ADR 0005) parte del plan original, así que mover visitas del plan rompería esa cuenta. No se encontró un plazo normativo para atender un desborde; el despacho de la reserva usa el mismo supuesto de 15 min que las averías (`PENDIENTE_VERIFICAR`).

## Decisión

Un desborde agrega una **recogida extra** y nunca mueve las visitas del plan, porque el contenedor se vuelve a llenar. Se queda con la visita del plan si ningún desvío llega antes. Si no, se desvía el compactador en servicio que **llega primero**, con cupo en el viaje y margen en el turno, y solo después de un lugar del que todavía no ha salido. Si ninguno puede, sale una reserva. Si el camión desviado se avería antes de llegar, la recogida extra se vuelve a despachar.

Se midieron las dos reglas para elegir el camión desviado, con 500 desbordes al azar sobre el plan de 9 compactadores de Suba (`mvn -pl rutea-app exec:java@overflows`, semilla 1):

| Regla | Reporte → recogida P50 | P95 | km agregados |
|---|---:|---:|---:|
| El que llega primero | 32,9 min | 102,6 min | 13.149,9 |
| El que menos se desvía | 45,4 min | 200,6 min | 11.575,6 |

Desviarse menos ahorra un 12 % de km, pero casi duplica el P95 de espera mientras el contenedor sigue desbordado. Por eso se elige el que llega primero.

## Consecuencias

- Las averías y los desbordes conviven sin que uno tenga que replanificar al otro.
- En el 28,4 % de los desbordes ningún compactador tiene cupo o turno y sale una reserva: el plan con el menor número de camiones deja poco margen, como ya se vio con las averías.
- En 78 de los 340 desbordes que recoge un camión en servicio, una reserva habría llegado antes. La regla prefiere no sacar un camión por un solo contenedor; si la operación fija un plazo, esta regla cambia.
- Pendiente: los sensores de llenado simulados generarán desbordes solos, y los aprovechables todavía no tienen operación en vivo.

## Remedición (2026-10-21)

Con el desempate de la matriz del ADR 0007 (`docs/benchmarks/f5-overflows/overflows-20261021-182034-482.csv`):

| Regla | Reporte → recogida P50 | P95 | km agregados |
|---|---:|---:|---:|
| El que llega primero | 31,7 min | 99,6 min | 13.019,9 |
| El que menos se desvía | 45,5 min | 207,3 min | 11.464,9 |

Sale una reserva en el 28,0 % de los desbordes, y en 75 de los 340 que recoge un camión en servicio una reserva habría llegado antes. Despachar tarda 41,9 µs (P50) y 163,3 µs (P99), más que antes (29,6 y 143,9 µs), porque cada tramo averigua qué red rige a su hora. La decisión no cambia.
