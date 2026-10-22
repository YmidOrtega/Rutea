# ADR 0009: Sensores de llenado simulados

- Estado: aceptada
- Fecha: 2026-10-21

## Contexto

La licitación de aseo de Bogotá permite a los operadores poner "chips de llenado RFID" en los contenedores (UAESP, Licitación 02 de 2017, Anexo 11), y Área Limpia ya tiene contenedores soterrados en Suba. Un sensor convierte los desbordes de ADR 0006, que hoy reporta una persona, en avisos automáticos. La pregunta es si eso acorta el tiempo que un contenedor pasa desbordado.

No hay inventario de contenedores con su tamaño, ni densidad de residuos sueltos medida en Bogotá, ni curva horaria de presentación. El estudio de caracterización de la UAESP (2017) midió la producción por estrato, pero la tabla no se encontró publicada. `docs/datos.md` registra cada supuesto.

## Decisión

1. **Modelo de llenado** (`rutea-sim`, `FillSensor`; `ContainerSensors`). Cada contenedor recibe su carga por recolección repartida en 7/6 días, pareja a lo largo del día, y se vacía cuando un camión llega a él. Antes del turno se vació en la recolección anterior, a la misma hora de su visita del plan: un día antes, o dos un lunes, porque se recoge de lunes a sábado. Cada contenedor tiene uno de los tres tamaños que declara el operador (3.200, 2.400 y 1.100 L), al azar. La densidad de los residuos sueltos es de 150 kg/m³, el valor de manual (`PENDIENTE_VERIFICAR`).
2. **Lecturas por WebSocket**, en pasos de 5 %, solo las que cambian. El mapa marca en ámbar los contenedores desde el 70 % y en rojo los desbordados; los demás no llevan marca.
3. **Despacho automático.** Cuando un contenedor llega al 100 % antes de que un camión llegue a él, el servidor lo reporta como desborde con causa "sensor" y lo despacha con la regla de ADR 0006. Uno lleno antes del turno se despacha a las 18:00.
4. **Ningún patrón por zona.** Sin la tabla por estrato, un patrón por zona sería inventado.

Medido con `mvn -pl rutea-app exec:java@sensors` (semilla 1, los 806 contenedores de Suba, CSV en `docs/benchmarks/f5-sensors/`). Contenedores que se desbordan antes de su visita del plan, sin despachar nada:

| Día | kg/m³ | 3.200 L | 2.400 L | 1.100 L | Mezcla |
|---|---:|---:|---:|---:|---:|
| Martes a sábado | 100 | 0 % | 0 % | 67,7 % | 21,3 % |
| Martes a sábado | 150 | 0 % | 0 % | 29,5 % | 8,6 % |
| Martes a sábado | 200 | 0 % | 0 % | 0 % | 0 % |
| Lunes | 100 | 32,9 % | 61,2 % | 100 % | 67,0 % |
| Lunes | 150 | 0 % | 19,0 % | 88,3 % | 34,5 % |
| Lunes | 200 | 0 % | 0 % | 67,7 % | 21,3 % |

Con los sensores despachando cada desborde apenas ocurre (mezcla de tamaños):

| Día | kg/m³ | Desbordes | Antes del turno | Los recoge su visita | Camión desviado | Lleno hasta la recogida, P50 sin sensores → con sensores | km agregados |
|---|---:|---:|---:|---:|---:|---:|---:|
| Martes a sábado | 150 | 70 | 29 | 65 | 5 | 3:28 → 3:26 | 37,6 |
| Lunes | 150 | 280 | 249 | 268 | 12 | 18:12 → 18:09 | 65,8 |

## Consecuencias

- Con un turno nocturno, los sensores casi no acortan el desborde. Un día cualquiera, el 41 % de los desbordes ocurre antes de las 18:00, cuando no hay camiones en la calle; un lunes, el 89 %. Los que ocurren durante el turno suelen ser poco antes de la visita del plan, que llega antes que un desvío.
- Lo que sí decide es el tamaño y el día. Los contenedores de 1.100 L y los lunes concentran los desbordes. Con 150 kg/m³, pasar los contenedores de 1.100 L a 2.400 L deja en cero los desbordes de un día cualquiera, y recoger también el domingo convierte el lunes (34,5 %) en un día cualquiera (8,6 %). Los sensores sirven para encontrar esos contenedores, no para correr detrás de ellos.
- El resultado depende de la densidad: con 100 kg/m³ se desborda el 21 % de los contenedores un día cualquiera; con 200 kg/m³, ninguno. Medirla en Suba es lo que más cambiaría estas cifras.
- Despachar por sensor suma kilómetros (37,6 km un día cualquiera) a cambio de casi nada: la regla de ADR 0006 se pensó para reportes de vecinos durante el turno. En vivo queda activa porque muestra la mecánica; para decidir, vale el benchmark.
- Pendiente: la tabla de producción por estrato del estudio de la UAESP (2017), para los patrones por zona, y la curva horaria de presentación.
