# ADR 0014: Puerta a puerta como CVRP sobre tramos de calle agrupados

- Estado: aceptada (2026-10-29, con la medición del paso 1 de F8)
- Fecha: 2026-10-29

## Contexto

Con la prórroga de los contratos de 2026, los operadores retiraron contenedores y pasaron la recolección de no aprovechables a puerta a puerta (ADR 0013, `docs/datos.md`). Lo que queda en contenedores es poco: en el modo Ciudad, Santa Fe sale con 0 contenedores, Chapinero con 2, Teusaquillo con 30 y Usaquén con 57. La operación en vivo de Suba se ve llena solo porque usa la base de 2022, que ya no describe la ciudad. Para que el modo en vivo sirva en cualquier localidad (F8), hay que modelar el puerta a puerta.

En el puerta a puerta el camión recorre la calle y recoge a lado y lado. Es un problema de rutas por arcos (CARP). El solver es un CVRP y no se va a reescribir: el roadmap ya preveía convertir cada tramo con demanda en un cliente.

**Medido en Suba** (`suba.rgraph`, extracto `colombia-261019`), tramos de calle local (terciaria, sin clasificar, residencial y calle compartida), contando una vez los de doble sentido:

| Clase | Tramos | km |
|---|---:|---:|
| Terciaria | 1.385 | 110,8 |
| Sin clasificar | 26 | 12,1 |
| Residencial | 7.710 | 571,0 |
| **Calle local** | **9.121** | **693,9** |

Un tramo mide 76 m en promedio: más o menos una cuadra. Con un cliente por tramo, la matriz de Suba tendría 9.122² celdas, unos 330 MB en `int`, y el solver trabajaría con 11 veces más clientes que los 807 de `SubaBase-n807-s1`.

**Demanda, con fuente:**
- El servicio ordinario lleva a Doña Juana unas **5.680 t/día** (UAESP, primer trimestre de 2026; `docs/datos.md`, "Cifras con fuente").
- La población urbana proyectada para 2026 es de **8.134.378** habitantes, **1.347.097** de ellos en Suba ([DANE, proyecciones por localidad 2018–2035](https://www.dane.gov.co/index.php/estadisticas-por-tema/demografia-y-poblacion/proyecciones-de-poblacion/proyecciones-de-poblacion-bogota), serie de 2021; hay una actualización de 2025, del Contrato 500 entre el DANE y la SDP, que no se encontró publicada).
- Repartidas por población, a Suba le tocan **940,6 t/día**. De esas, lo que recoge el cargue lateral de sus contenedores ya está en el plan de la ciudad, y el resto es puerta a puerta.

## Decisión


1. **Un cliente por grupo de tramos, no por tramo.** Los tramos de calle local se agrupan en trozos contiguos, cada uno de longitud acotada. Cada trozo es un cliente del CVRP: el camión entra por un extremo, lo recorre recogiendo y sale por el otro. La matriz va de la salida de un trozo a la entrada del siguiente. El recorrido interno del trozo entra como tiempo de servicio, a velocidad de recolección, y no como distancia de la matriz.
2. **Demanda por longitud de calle.** Las toneladas de la localidad (las de la ciudad repartidas por población, menos lo que recogen sus contenedores) se reparten entre los trozos según su longitud, el mismo indicador que ya usan los contenedores simulados. Es un supuesto, y así se marca: con más datos, por ejemplo edificios o viviendas por manzana, se reemplaza.
3. **Los dos esquemas en la misma instancia.** Los contenedores de la localidad siguen siendo clientes puntuales, y los trozos se suman a ellos. El solver, el `Evaluator` y el arnés no cambian.
4. **Trozos de hasta 500 m**, decididos midiendo (abajo). Un trozo es una caminata por calles locales en su sentido de circulación (`StreetChunks`): crece por la calle que menos gira, hacia adelante y después hacia atrás desde su entrada, y un tramo entra solo si el trozo no pasa de 500 m. Un tramo que ya mide más queda como trozo propio.
5. **Una instancia por franja: grupo de días y turno** (2026-10-30). Con 3 pasadas por semana en seis días hay dos grupos de días (lunes, miércoles y viernes; martes, jueves y sábado), y cada uno tiene turno de día (06:00–16:00) y de noche (18:00–04:00), los de las microrrutas domiciliarias que publica el ASE 1. Las UPZ de la localidad se reparten entre las cuatro franjas, parejas en metros de calle: de la más larga a la más corta, cada una a la franja que lleva menos (`DoorToDoor`). La UPZ es el sector en que ya se publican los horarios (R04), así que el calendario sale listo para publicar. Cada contenedor va en su turno de cargue lateral; los que se recogen 6 veces por semana, en los dos grupos de días, y los de 3, en el de su UPZ. Partir solo por días dejaba un día de Suba en más de 40 s de ALNS. Resultado en el roadmap, F8.

## Medición

`mvn -pl rutea-app exec:java@door-to-door` (2026-10-29; i5-14400F, 16 hilos, Java 21.0.13; ALNS con semilla 7 y 2000 iteraciones; CSV en `docs/benchmarks/f8-door-to-door/`). Una recolección de toda Suba: 940,6 t/día por los días entre dos pasadas, con 3 pasadas por semana (`PENDIENTE_VERIFICAR`), son 2.194,8 t, 3,163 kg por metro de calle local. Compactadores de 8 t. Base y portería, las del cargue lateral de 2026. El costo incluye lo que se recorre recogiendo. La zona chica son los tramos a menos de 900 m del centro de las calles locales de Suba.

| Zona | Trozo máx. | Clientes | m por trozo | Matriz | ALNS | km | Viajes |
|---|---:|---:|---:|---:|---:|---:|---:|
| zona | sin agrupar | 475 | 65 | 55 ms | 5,4 s | 601,6 | 13 |
| zona | 150 m | 280 | 110 | 31 ms | 3,3 s | 602,8 | 13 |
| zona | 300 m | 216 | 143 | 23 ms | 2,7 s | 602,8 | 13 |
| zona | **500 m** | **189** | **163** | 20 ms | **2,3 s** | **601,8** | 13 |
| zona | 800 m | 186 | 166 | 20 ms | 2,3 s | 603,8 | 13 |
| zona | 1.200 m | 185 | 167 | 19 ms | 2,2 s | 603,8 | 13 |
| Suba | 150 m | 5.505 | 126 | 638 ms | 121,4 s | 14.185,9 | 279 |
| Suba | 300 m | 3.904 | 178 | 413 ms | 73,4 s | 14.187,0 | 279 |
| Suba | **500 m** | **3.269** | **212** | 348 ms | **59,8 s** | **14.191,8** | 278 |
| Suba | 800 m | 2.979 | 233 | 313 ms | 52,3 s | 14.149,7 | 278 |
| Suba | 1.200 m | 2.872 | 242 | 298 ms | 50,2 s | 14.153,1 | 277 |

Lo que dice:
- **Agrupar casi no cuesta.** En la zona, todos los tamaños quedan a menos de 0,4 % de los tramos sueltos, dentro de lo que varía ALNS de una corrida a otra. En toda Suba, los km se mueven 0,3 % entre tamaños.
- **Agrupar sí ahorra tiempo.** Con 500 m, la zona resuelve en menos de la mitad (2,3 s frente a 5,4 s), y Suba pasa de 9.121 tramos a 3.269 clientes.
- **Más allá de 500 m casi no cambia.** Los trozos se estancan cerca de 170 m en la zona y 240 m en Suba, porque las calles se cortan, cambian de sentido o ya están en otro trozo. De 500 a 1.200 m los clientes de Suba bajan un 12 %, a cambio de trozos más gruesos para replanificar una avería.
- **Primera versión, solo hacia adelante:** se estancaba en unos 142 m y daba 4.811 clientes con 150 m en Suba. Dejaba además pasar el último tramo entero, y un trozo de 3.030 m no cupo en un camión. Las dos cosas están corregidas en la versión medida.
- La instancia de toda Suba es una cota alta: en la operación, cada día sale solo una parte de las calles. Aun así, 60 s de ALNS es demasiado para un plan "en vivo", y el paso de la instancia por localidad tiene que partirla por día y turno.

## Consecuencias

- Se reutilizan el solver, el `Evaluator`, el arnés y la operación en vivo, porque un trozo es un cliente más.
- Agrupar pierde optimalidad, porque el orden dentro de un trozo queda fijo, pero lo medido está dentro del ruido de ALNS.
- La demanda por longitud no distingue estratos ni densidad. La UAESP midió producción por estrato y localidad en 2017, pero la tabla no está publicada (`docs/datos.md`, "Patrón por zona").
- Quedan `PENDIENTE_VERIFICAR`: la frecuencia del puerta a puerta (cuántas veces por semana pasa por cada calle), la velocidad de recolección y la capacidad de los compactadores de carga trasera. Ninguna está publicada por los operadores en lo que se ha encontrado.
- Las avenidas quedan fuera de la primera versión: el puerta a puerta sobre arterias tiene otra logística (cruces, carriles exclusivos) y su demanda no se puede separar con lo que hay.
