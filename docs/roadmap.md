# Roadmap

Cada fase cierra con un criterio medible. No se empieza la siguiente sin cumplirlo, salvo que se registre la excepción aquí.

Duraciones orientativas, trabajando unas 10 horas por semana.

| Fase | Nombre | Estado |
|---|---|---|
| F0 | Fundamentos | Completa |
| F1 | Primer solver | Completa |
| F2 | Metaheurística y paralelismo | Completa |
| F3 | Mapa real: Suba completa | Completa |
| F4 | Restricciones del dominio | Completa |
| F5 | Operación en tiempo real | Completa |
| F6 | Producto y publicación | Completa |
| F7 | Bogotá por ASE | Completa |

## F0 — Fundamentos (1–2 semanas)

- [x] Documentación inicial (AGENTS.md, normativa, arquitectura, diseño, ADRs).
- [x] Instancias CVRPLIB del set X (n ≤ 200) con sus BKS en `data/cvrplib/X`.
- [x] Estructura Maven multi-módulo y frontend Vite.
- [x] Lector de instancias y soluciones CVRPLIB (`EUC_2D`).
- [x] Modelo de instancia y solución; evaluador de costo y factibilidad.
- [x] Solver de referencia (vecino más cercano con capacidad) para probar el arnés de punta a punta.
- [x] Arnés de benchmarks que escribe CSV.
- [x] Dominio inicial: corrientes de residuo (Res. 2184/2019) y reglas de recolección con `NormRef`.
- [x] Mapa de demo con datos simulados sobre cuadrícula, generados por el solver de referencia (`pnpm demo:data`).
- [x] Correr `mvn verify` en local y en CI.
- [x] Verificar contra el texto oficial al menos R02, R03 y R09 de `docs/normativa.md`.

**Criterio de cierre:** el evaluador reproduce el costo publicado de todas las BKS de `data/cvrplib/X` y el arnés produce un CSV con el gap del solver de referencia.

## F1 — Primer solver (2–3 semanas)

- Clarke-Wright (ahorros, versión paralela).
- Búsqueda local: 2-opt intra-ruta, relocate, swap, 2-opt* inter-ruta.
- Evaluación de movimientos en O(1) con cargas acumuladas por ruta.
- Listas de vecinos (granular) para no explorar movimientos inútiles.
- Matrices asimétricas desde el inicio: con calles de un solo sentido, ir de A a B no cuesta lo mismo que de B a A. Los movimientos que invierten tramos (2-opt) recalculan el costo en el sentido nuevo, y se prueban también con instancias asimétricas, porque las de CVRPLIB son todas simétricas.

**Criterio de cierre:** gap promedio menor a 5 % en las instancias X con n ≤ 200 de `data/cvrplib`.

**Resultado** (semilla 1, i5-14400F, Java 21): `clarke-wright` 6,44 %; `local-search` **3,43 %**, entre 15 y 36 ms por instancia. Hizo falta arrancar desde Clarke-Wright con varios λ (parámetro de forma del ahorro): desde un solo arranque la búsqueda local se quedaba en 5,16 %. Se sumó Or-opt (tramos de 2 y 3 clientes, también invertidos) a los movimientos previstos.

## F2 — Metaheurística y paralelismo (3–4 semanas)

- ALNS: destrucción (aleatoria, por relación de Shaw, peor costo, por cadena) y reparación (voraz, regret-k).
- Pesos adaptativos de operadores y criterio de aceptación (recocido simulado o record-to-record).
- Varias búsquedas en paralelo con semillas distintas; se queda la mejor.
- Microbenchmarks con JMH de la evaluación de movimientos.
- Comparación contra Timefold con el mismo límite de tiempo (solo en el arnés, nunca como dependencia del solver).

**Criterio de cierre:** gap promedio de 2–3 % en instancias X de hasta ~300 clientes con un límite de 60 s, y tabla comparativa publicada.

**Resultado** (2026-10-20; 43 instancias X de 101 a 301 nodos; 60 s; semilla 1; i5-14400F, 16 hilos, Java 21.0.12; las tres corridas a la vez en procesos separados). CSV completos en `docs/benchmarks/f2-60s/`.

| Solver | Gap promedio | n ≤ 200 | n 201–300 | Peor instancia | Llega a la BKS |
|---|---:|---:|---:|---:|---:|
| `alns-x4` (4 búsquedas en paralelo) | **0,19 %** | 0,10 % | 0,29 % | 0,69 % | 10 |
| `alns` | 0,35 % | 0,26 % | 0,44 % | 2,02 % | 8 |
| `timefold` 2.7.1, configuración por defecto | 2,01 % | 1,58 % | 2,46 % | 6,11 % | 0 |

Las 129 soluciones son factibles según el `Evaluator`. Timefold no queda primero en ninguna instancia. JMH (`exec:exec@jmh`): una pasada completa de búsqueda local sobre un óptimo local tarda 166 µs en X-n101, 345 µs en X-n200 y 584 µs en X-n298, unos 5 ns por movimiento evaluado.

En frío y en caliente (2026-10-22; `alns-x4`, mismas 43 instancias, 60 s, semilla 1, misma máquina; las dos corridas a la vez). En frío, un proceso por instancia; en caliente, cada instancia se resuelve una vez y se descarta antes de medir (`--warmup`). CSV en `docs/benchmarks/f2-60s/frio/` y `caliente/`.

| `alns-x4` | Gap promedio | Peor instancia | Llega a la BKS |
|---|---:|---:|---:|
| En frío | **0,18 %** | 0,64 % | 12 |
| En caliente | 0,19 % | 0,93 % | 8 |

Las 86 soluciones son factibles. La diferencia entre frío y caliente está dentro de la dispersión de una búsqueda con límite de tiempo: con 60 s, calentar la JVM no cambia el resultado.

Pendiente de F2: comparar contra Timefold afinado (aquí va con su configuración por defecto).

## F3 — Mapa real: Suba completa (3–4 semanas)

> **806 contenedores simulados (supuesto de 2022).** Las cifras de Suba de esta fase salen de los 806 contenedores negros simulados con la base de 2022 (`SubaBase-n807-s1`, o la semilla 7 en el plan de demo). La Suba de 2026 tiene 320 en su Plan de Contenerización, y la diferencia está medida en F7 (ADR 0013).

Decidido el 2026-10-20: el objetivo deja de ser la solución perfecta sobre instancias de prueba y pasa a ser una solución que funcione en el mundo real, para tomar decisiones con base en ella. Por eso F3 cubre toda la localidad y adelanta de F4 los viajes de descarga.

- Contenedores **simulados realistas** en toda Suba: unos 1.700, sobre calles residenciales reales y con densidad según la vivienda. La cifra sale de repartir por población los 10.746 contenedores que tenía Bogotá en 2022. Se reemplazan cuando aparezca un inventario oficial (UAESP o Torca, operador del ASE 5).
  - Fuentes verificadas (2026-10-20): la UAESP cita "los 10.746 contenedores con los que contaba la ciudad" ([Bogotá.gov.co, 9 de marzo de 2022](https://bogota.gov.co/mi-ciudad/habitat/contenedores-soterrados-mejoraran-disposicion-de-residuos-en-bogota)). Según la UAESP en 2025, la licitación exigió contenerizar "más de 12,5 millones de litros": "más de 10.700 contenedores", "la mitad para residuos no aprovechables y la mitad para promover el reciclaje", y después "se pidieron 490 contenedores más" ([Bogotá.gov.co, 26 de febrero de 2025](https://bogota.gov.co/mi-ciudad/habitat/contenedores-de-basuras-en-bogota-apoyan-prestacion-servicio-aseo-2025)).
  - Consecuencia: solo la mitad negra (no aprovechables) va a Doña Juana. La blanca es ruta selectiva hacia una ECA (Decreto 596 de 2016).
  - El reparto por población quedó reemplazado por el 15 % del ASE 5 en las toneladas de la ciudad (PGIRS): 10.746 × 15 % × 1/2 = 806 contenedores negros. Cifras y fuentes en `docs/datos.md`. (Corrección del 2026-10-28, en F7: ese 15 % es la parte del suelo urbano, no de las toneladas; se sigue usando como aproximación.)
- Red vial: todas las calles de Suba más las vías principales de Bogotá hasta el relleno Doña Juana (Usme).
- Operación con **viajes a Doña Juana**: el camión se llena, descarga y vuelve; turno nocturno; tiempo por contenedor. Datos de referencia: ~5.600 t/día de ordinarios en Bogotá (UAESP, 2025), unas ~900 t/día estimadas para Suba por población, compactadores de 16 y 25 yardas con 7–10 t por viaje y 40–90 min de descarga en el relleno.
- Escala: la memoria de `Plan` (n²) y los ahorros de Clarke-Wright (todos los pares) hay que ajustarlos para ~1.700 nodos.
- Métricas para decidir: camiones necesarios, km, horas, toneladas por viaje y CO₂, frente a un barrido ingenuo.
- Extracto OSM con la red de Suba y el corredor a Doña Juana.
- Grafo dirigido compacto (sentidos viales, velocidades por tipo de vía).
- Dijkstra paralelo desde cada contenedor para construir la matriz; A* para consultas puntuales.
- Recalcular solo lo afectado cuando se cierra una vía.
- El frontend reemplaza la cuadrícula simulada por las geometrías reales.

**Criterio de cierre:** el solver planifica la zona piloto y las rutas se dibujan sobre calles reales. **Cumplido** el 2026-10-20.

**Avance** (2026-10-20; extracto `colombia-261019`; i5-14400F, 16 hilos, Java 21.0.12):

- [x] Lector PBF propio: lee los 333 MB de Colombia en 0,95 s en frío y 0,64 s en caliente, y coincide con libosmium en conteos y sumas de coordenadas.
- [x] Grafo dirigido compacto (CSR), con sentidos viales y velocidades por tipo de vía (supuestas, no medidas). Se construye en 5,4 s: 18.134 vértices (8.195 en Suba), 34.071 arcos y 2.227 km de calles. Pesa 1,86 MB en `.rgraph`. La poda a la componente fuertemente conexa quita 99 de los 8.294 vértices de Suba (1,2 %).
- [x] Dijkstra.
- [x] Sitios ancla en el grafo: la base de Torca en Fontibón (Av. Calle 17 × Carrera 124) y la portería noreste de Doña Juana (nodo `barrier=swing_gate` sobre el borde del relleno), con sus vías privadas de acceso. El lector PBF lee ahora las etiquetas de nodo y coincide con libosmium (639.874 nodos con etiquetas). Con los sitios, el grafo tiene 18.729 vértices, 35.364 arcos y 2.287 km. Centro de Suba → portería: 30,6 km de ida y 32,9 km de vuelta. Base → centro de Suba: 18,7 km.
- [x] Calibración: desde el centroide del ASE 5 del PGIRS, el grafo da 29,1 km hasta la portería; el PGIRS dice 32,589 km (10,7 % más). Ver `docs/datos.md`.
- [x] Matriz de distancias y tiempos en paralelo (`TravelMatrix`): 1.701 × 1.701 sobre el grafo real en 377 ms con 16 hilos y 3,6 s con uno. Sale idéntica en los dos casos; el 97,5 % de las celdas es asimétrico.
- [x] Contenedores negros simulados (hoy 806, ver `docs/datos.md`) (`SimulatedContainers`, semilla 1) sobre 693,9 km de calle local de Suba (terciaria, sin clasificar, residencial y `living_street`), repartidos según la longitud de calle porque los edificios de OSM en Suba están incompletos. Separación mínima de 50 m; la mediana al vecino más cercano es de 116 m. La matriz con el relleno (851 × 851) sale en 230 ms; la corrida se hizo con 850.
- [x] Demanda: 160 kg de media por contenedor y recolección (110.760 kg/día en contenedores × 7/6 ÷ 806), con fuentes en `docs/datos.md`.
- [x] Escala del solver: con 807 nodos no hace falta ajustar nada (la búsqueda local termina en 0,7 s; ningún solver pasa de ~300 MB). El ajuste previsto era para ~1.700 nodos.
- [x] Benchmark de Suba (`data/suba/Suba-n807-s1.vrp`, sin BKS): 806 contenedores, 128.454 kg y compactadores de 8 t, con la portería de Doña Juana como depósito. 60 s; semillas 1, 2 y 3; i5-14400F, 16 hilos, Java 21.0.12; hasta 7 procesos a la vez. CSV en `docs/benchmarks/f3-suba-60s/`.

  | Solver | En frío (prom.; mín.–máx.) | En caliente (prom.; mín.–máx.) | Viajes |
  |---|---:|---:|---:|
  | `nearest-neighbor` | 1.400,1 km | 1.400,1 km | 17 |
  | `clarke-wright` | 1.295,2 km | 1.295,2 km | 17 |
  | `local-search` | 1.259,5 km; 1.258,7–1.260,4 | igual | 17 |
  | `alns` | 1.242,7 km; 1.237,0–1.247,7 | 1.240,5 km; 1.239,6–1.241,0 | 17 |
  | `alns-x4` | **1.237,5 km**; 1.234,0–1.243,7 | **1.237,8 km**; 1.233,2–1.241,8 | 17 |
  | `timefold` 2.7.1, configuración por defecto | 1.675,0 km; 1.644,3–1.693,6 | 1.605,2 km; 1.596,9–1.614,8 | 17 |

  Las 36 soluciones son factibles según el `Evaluator`, y todas usan el mínimo de 17 viajes. Al menos 788,5 km son inevitables: 17 viajes × (24,1 + 22,3) km hasta el contenedor más cercano al relleno, ida y vuelta. Con límite de tiempo, el resultado depende de la carga de la máquina: `alns` con semilla 1, solo en la máquina, dio 1.231,8 km; aquí, con 7 procesos a la vez, 1.247,7 km.
- [x] Frontend sobre las geometrías reales: `pnpm demo:data` resuelve Suba con ALNS (semilla 7, 2.000 iteraciones, ~16 s) y dibuja los 17 viajes calle por calle (37.374 puntos, 1.224,1 km). Las horas de paso salen de las velocidades del grafo. El planificador verifica que cada tramo dibujado mida lo mismo que la matriz que optimizó el solver.
- [x] Turno completo de cada compactador: base de Fontibón → contenedores → portería de Doña Juana → base. La instancia (`SubaBase-n807-s1`) usa el nodo 0 como dos lugares gracias a la matriz asimétrica: su fila sale de la base y su columna llega a la portería. El regreso es fijo por camión y no cambia la optimización. El benchmark de arriba es del modelo anterior (`Suba-n807-s1`, con la portería como depósito en los dos sentidos).
- [x] Parada de 60 s en cada contenedor (`PENDIENTE_VERIFICAR`; ver `docs/datos.md`). Plan de demo (semilla 7): 1.400,2 km con los regresos; turnos de 2,57 a 4,57 h (3,28 h en promedio) y de 31 a 55 contenedores. Saliendo a las 18:00, el último vuelve a la base a las 22:34, dentro del turno de 18:00 a 02:00 sin contar la descarga. Hay margen para un segundo viaje por turno (F4).
- [x] Benchmark del modelo con base (`SubaBase-n807-s1`, sin BKS), en las mismas condiciones que el anterior: 60 s; semillas 1, 2 y 3; i5-14400F, 16 hilos, Java 21.0.12; hasta 7 procesos a la vez. El costo es el del solver: base → contenedores → portería, sin el regreso fijo a la base. CSV en `docs/benchmarks/f3-suba-base-60s/`.

  | Solver | En frío (prom.; mín.–máx.) | En caliente (prom.; mín.–máx.) | Viajes |
  |---|---:|---:|---:|
  | `nearest-neighbor` | 1.177,0 km | 1.177,0 km | 17 |
  | `clarke-wright` | 1.122,1 km | 1.122,1 km | 18 |
  | `local-search` | 1.071,9 km; 1.064,7–1.077,5 | igual | 17–18 |
  | `alns` | 1.019,7 km; 1.013,8–1.023,8 | 1.021,5 km; 1.021,0–1.021,9 | 17 |
  | `alns-x4` | **1.017,7 km**; 1.013,0–1.020,0 | **1.021,3 km**; 1.019,7–1.022,5 | 17 |
  | `timefold` 2.7.1, configuración por defecto | 1.376,3 km; 1.366,6–1.383,1 | 1.355,6 km; 1.338,1–1.364,5 | 17 |

  Las 36 soluciones son factibles según el `Evaluator`. Clarke-Wright no minimiza la cantidad de viajes y aquí usa uno de más; ALNS siempre llega al mínimo de 17. Timefold queda un 35 % por encima de `alns-x4` en frío. Entre `alns` y `alns-x4`, y entre frío y caliente, las diferencias (≤ 0,4 %) están dentro de la dispersión entre semillas.

## F4 — Restricciones del dominio (3 semanas)

> **806 contenedores simulados (supuesto de 2022).** Las cifras de Suba de esta fase salen de los 806 contenedores negros simulados con la base de 2022 (`SubaBase-n807-s1`, o la semilla 7 en el plan de demo). La Suba de 2026 tiene 320 en su Plan de Contenerización, y la diferencia está medida en F7 (ADR 0013).

- Ventanas horarias y turnos (R02).
- Varios viajes por turno: ruta → descarga → ruta.
- Flota heterogénea: compactadores para no aprovechables, vehículos de carga para aprovechables (R06, R10).
- Frecuencias semanales por zona (R03).
- Paradas sin compactación (R07).
- Reporte por ruta: km, horas, toneladas, CO₂ estimado a partir del consumo de diésel.

**Criterio de cierre:** comparación contra un barrido ingenuo por cuadrantes en la zona piloto, con ahorro en km y CO₂. **Cumplido** el 2026-10-20: 20,5 % menos km y CO₂ que el mejor barrido, y 25,3 % menos km si se encadenan viajes. Quedan pendientes de datos, no de código: el inventario de ECA, el calendario semanal por zona (R03) y el efecto de R07 sobre la capacidad.

**Avance** (2026-10-20; i5-14400F, Java 21.0.12):

- [x] Línea base `QuadrantSweep`: cuadrantes recorridos en serpentina de norte a sur, franjas de 200 m dentro de cada uno, y llenado secuencial hasta 8 t. Se prueba con varios tamaños de cuadrante y se compara contra el mejor.
- [x] Reporte por turno y por plan (`PlanReport`): km con el regreso a la base, horas, toneladas, litros y CO₂. Usa el factor del IPCC 2006 (2,68 kg CO₂/L) y un consumo supuesto de 0,8 L/km (`PENDIENTE_VERIFICAR`). El panel del mapa muestra las horas de camión y el CO₂ estimado.
- [x] Comparación (`mvn -pl rutea-app exec:java@baseline`; `SubaBase-n807-s1`; ALNS con semilla 1 y 2.000 iteraciones, determinista). CSV en `docs/benchmarks/f4-baseline/`.

  Un camión por viaje en los dos planes; las horas cuentan las paradas y una descarga de 90 min por viaje:

  | Método | Turnos | km | Horas | t CO₂ | Turnos de más de 8 h |
  |---|---:|---:|---:|---:|---:|
  | Barrido, cuadrantes de 500 m | 17 | 1.816,7 | 96,4 | 3,89 | 1 |
  | Barrido, cuadrantes de 1 km | 17 | 1.783,9 | 95,3 | 3,82 | 0 |
  | **Barrido, cuadrantes de 2 km (mejor)** | 17 | 1.775,3 | 95,2 | 3,80 | 0 |
  | Barrido, cuadrantes de 4 km | 17 | 1.858,6 | 97,5 | 3,98 | 1 |
  | **Rutea (`alns`)** | 17 | **1.410,9** | **81,4** | **3,02** | 0 |

  Horas medidas de nuevo el 2026-10-21: entre caminos igual de cortos, la matriz ahora toma el más rápido (ADR 0007). Los km no cambian.

  Frente al mejor barrido: **20,5 % menos km, 14,5 % menos horas y 20,5 % menos CO₂**, unas 0,78 t de CO₂ menos por día de recolección con el consumo supuesto. Las horas bajan menos que los km porque las paradas y las descargas son iguales en los dos planes. Si además se encadenan viajes (abajo), Rutea baja a 1.326,5 km: 25,3 % menos que el mejor barrido. El barrido es una línea base ingenua, no las microrrutas reales de Torca, que no son públicas.
- [x] Varios viajes por turno (`ShiftScheduler`): los viajes de ALNS se reparten entre camiones con programación dinámica exacta sobre los subconjuntos de viajes. Minimiza primero los camiones y después los km, con hasta 3 viajes por camión en un turno de 8 h que cuenta paradas, descargas y regreso. Un viaje que ni solo cabe queda marcado como horas extra. Para que distancia y tiempo hablen del mismo recorrido, `TravelMatrix` ahora da los segundos del camino más corto en metros, no los del más rápido (y hace la mitad de los Dijkstra).

  Los 17 viajes de ALNS (`SubaBase-n807-s1`, semilla 1, 2.000 iteraciones), con la descarga en los dos extremos del rango publicado:

  | Reparto | Camiones | km | Horas de camión | Último regreso |
  |---|---:|---:|---:|---:|
  | Un viaje por camión | 17 | 1.410,9 | 81,4 | 23:54 |
  | Mínimo de camiones, descarga de 40 min | **9** (8 con 2 viajes) | **1.326,5** | **64,5** | 01:55 |
  | Mínimo de camiones, descarga de 90 min | 16 (1 con 2 viajes) | 1.398,7 | 81,0 | 01:46 |

  Encadenar viajes ahorra km, porque el segundo viaje sale de la portería y se ahorra la salida desde la base y un regreso. Y el tiempo de descarga en Doña Juana define el tamaño de la flota: con 40 min bastan 9 camiones; con 90, hacen falta 16. En el barrido ingenuo, algunos viajes ni solos caben en el turno (cuadrantes de 500 m y de 4 km).
- [x] Plan de demo con camiones de varios viajes (semilla 7, descarga de 40 min): 9 camiones para 17 viajes, 1.310,6 km y turnos de 5:14 a 7:49 h (7:50 antes del desempate del ADR 0007).
- [x] Normativa en el plan (`Compliance`): R02, R03 y R07, verificadas en el texto oficial, se revisan sobre cada parada con la hora de paso del planificador y 644 sitios sensibles de OSM. Plan de demo: **79 contenedores sin compactación** (R07, a 50 m o menos de un colegio o centro de salud), **335 paradas nocturnas** que requieren medidas contra el ruido (R02) y frecuencia de 6 veces por semana, que cumple el mínimo de 2 (R03). Con salida a las 18:00, 383 de las 806 paradas caen entre las 21:00 y las 06:00. Con las mismas horas de paso y salida a las 06:00, ninguna: irían de las 06:22 a las 11:53.
- [ ] Calendario semanal por zona (R03, VRP periódico) y efecto de R07 sobre la capacidad.
- [x] Flota heterogénea (R06, R10), con supuestos. R10 se reverificó: el capítulo que el Decreto 596 de 2016 agregó al Decreto 1077 aparece derogado y lo sustituyó el Decreto 1381 de 2024 (exclusividad de las organizaciones de recicladores por 15 años y transporte selectivo hasta la ECA). Los aprovechables van en vehículos sin compactación de una organización de recicladores, que salen de una ECA y vuelven a ella; R07 no aplica a esos vehículos. Plan de demo: 9 compactadores (17 viajes, 1.310,6 km) y 6 vehículos de aprovechables (17 viajes, 429,9 km, 24,7 t). Las cifras de aprovechables descansan en supuestos sin dato público (ECA, toneladas, vehículos; ver `docs/datos.md`) y el panel los lista.
- [ ] Datos de aprovechables: inventario de ECA de la UAESP, toneladas por organización y vehículos.

## F5 — Operación en tiempo real (3 semanas)

> **806 contenedores simulados (supuesto de 2022).** Las cifras de Suba de esta fase salen de los 806 contenedores negros simulados con la base de 2022 (`SubaBase-n807-s1`, o la semilla 7 en el plan de demo). La Suba de 2026 tiene 320 en su Plan de Contenerización, y la diferencia está medida en F7 (ADR 0013).

- Reloj de simulación acelerable.
- Sensores de llenado simulados, con patrones por zona y día.
- Eventos: avería (R05), vía cerrada, contenedor desbordado.
- Replanificación incremental: se congela lo recorrido y ALNS arranca desde el plan vigente con límite de tiempo.
- Posiciones y eventos por WebSocket (R08).

**Criterio de cierre:** una avería produce un plan nuevo con latencia P99 medida y reportada. **Cumplido** el 2026-10-20: P99 de 144,3 ms (124,6 ms medido de nuevo el 2026-10-21, con la matriz desempatada del ADR 0007).

**Avance** (2026-10-20; i5-14400F, 16 hilos, Java 21.0.12):

- [x] Replanificación ante averías (ADR 0005, R05). Se congela lo recorrido; los contenedores que el camión averiado no alcanzó a recoger los cubren camiones de reserva desde la base. ALNS arranca en caliente desde el plan vigente (`AlnsSolver.solveFrom`; los costos del arnés en el set X son idénticos antes y después) con 300 iteraciones.
- [x] Latencia medida (`mvn -pl rutea-app exec:java@breakdowns`): 500 averías con camión y hora al azar (semilla 1) sobre el plan de 9 camiones de Suba, más 20 de calentamiento. CSV en `docs/benchmarks/f5-breakdowns/`.

  | Medida | P50 | P95 | P99 | Máx. |
  |---|---:|---:|---:|---:|
  | Latencia, arranque en caliente | 33,9 ms | 102,3 ms | **124,6 ms** | 143,0 ms |
  | Latencia, ALNS desde cero | 36,4 ms | 108,8 ms | 128,7 ms | 138,4 ms |
  | Contenedores huérfanos | 38 | 96 | 102 | 103 |
  | Avería → reserva en el primer huérfano | 39,9 min | 54,7 min | 67,3 min | 76,4 min |

  Medido de nuevo el 2026-10-21, con la matriz desempatada (ADR 0007); la primera medición dio un P99 de 144,3 ms. En frío (la primera avería, con la JVM sin calentar): 5,6 ms. R05 se cumple en las 500 averías: la reserva llega en 76 min en el peor caso, con un despacho supuesto de 15 min (`PENDIENTE_VERIFICAR`). Con 300 iteraciones, arrancar en caliente o desde cero da casi lo mismo (+0,06 % de km): en problemas de este tamaño ALNS converge rápido de cualquier forma. Lo que gana el arranque en caliente es que el plan nuevo conserva el orden que ya conocían los conductores.
- [x] Operación en vivo (R05, R08). `rutea-app` sirve el plan (`GET /api/plan`, armado en segundo plano al arrancar), simula averías (`POST /api/breakdowns`) y las difunde por WebSocket (`/ws/events`) a todos los clientes; `GET /api/events` da el historial a quien llega tarde. En el mapa, cada compactador tiene un botón para averiarlo a la hora del reloj: el camión se detiene, sus contenedores huérfanos se marcan en rojo, la reserva aparece en el acento y el panel registra el evento con su latencia y R05. Sin la API, el mapa usa el plan estático y no ofrece averías.
- [x] Reasignar huérfanos a camiones en servicio con margen (`LivePlan`, `Insertion`), medido frente a la reserva en las mismas 500 averías (ADR 0005, actualización). Reasignar primero recoge el primer huérfano a los 13,2 min (P50) en vez de 39,9, pero suma un 19,3 % de km. Reasignar solo si así se evita la reserva ahorra un 1,9 % de km y 12 camiones de reserva, pero aplica en 12 de 500 averías: el plan de menos camiones deja poco margen. (Cifras de la remedición del 2026-10-21.)
- [x] Contenedor desbordado (ADR 0006). Un clic en un contenedor negro lo reporta a la hora del reloj (`POST /api/overflows`). Se queda con su visita del plan si ningún desvío llega antes; si no, se desvía el compactador que llega primero, con cupo y turno; si ninguno puede, sale una reserva. Medido con 500 desbordes (`mvn -pl rutea-app exec:java@overflows`, CSV en `docs/benchmarks/f5-overflows/`): despachar tarda 41,9 µs (P50) y 163,3 µs (P99); del reporte a la recogida pasan 31,7 min (P50) y 99,6 min (P95). Una reserva sale en el 28,0 % de los casos. Desviar el camión que menos se desvía ahorra un 11,9 % de km, pero duplica el P95 de espera (207,3 min). (Cifras de la remedición del 2026-10-21.)
- [x] Vía cerrada (ADR 0007). "Cerrar una vía", en el panel, y un clic en el mapa cierran la calle más cercana a la hora del reloj (`POST /api/closures`). La matriz se repara en vez de recalcularse: solo las filas y columnas cuyos caminos pasaban por la vía, con un resultado idéntico al de calcularla de cero. Cada tramo usa la red vigente cuando el camión arranca; si la vía se cierra con el camión en camino, este gira. Los contenedores sin acceso salen del plan, y los camiones afectados reordenan lo que les falta. Medido con 500 cierres sobre calles que un compactador tenía por delante (`mvn -pl rutea-app exec:java@closures`, CSV en `docs/benchmarks/f5-closures/`): reparar la matriz tarda 9,2 ms (P50) y 41,7 ms (P99), frente a 87,8 y 106,0 ms de calcularla de cero, y la matriz es idéntica en los 500. Reparar y replanificar tarda 13,1 ms (P50) y 46,3 ms (P99). Reordenar agrega un 20,5 % menos de km que solo desviar (294,1 frente a 370,1 km en total). 57 cierres dejan contenedores sin acceso y en 15 un camión termina fuera de turno.
- [x] Reloj y posiciones transmitidos por el servidor (ADR 0008, R08). El reloj de la simulación es el del servidor (`rutea-sim`): corre a 15, 60 o 240×, es el mismo para todos los clientes y marca la hora de cada evento. Cuatro veces por segundo, el servidor reporta por WebSocket cada vehículo que se movió, con su estado y lo recorrido desde el reporte anterior. El mapa dibuja esos reportes, no el plan. A 60× con 15 vehículos son 8,4 KB/s (2,1 KB por reporte de media, 3,5 KB en P95).
- [x] Sensores de llenado simulados (ADR 0009). Cada contenedor de no aprovechables tiene un sensor que transmite su llenado en pasos de 5 %. Si llega al 100 % antes de que un camión llegue a él, el servidor lo despacha solo, como un desborde. El modelo usa la carga simulada, los tamaños que declara el operador y 150 kg/m³ de densidad (`PENDIENTE_VERIFICAR`). El patrón por día sale de la frecuencia: el lunes se acumulan dos días. No hay patrón por zona, porque no se encontró la producción por estrato. Medido con `mvn -pl rutea-app exec:java@sensors`: un día cualquiera se desborda el 8,6 % de los contenedores (29,5 % de los de 1.100 L); un lunes, el 34,5 %. Con turno nocturno, los sensores no acortan el desborde (P50 de 3:28 a 3:26), porque casi la mitad ocurre antes de las 18:00. Lo que decide es el tamaño del contenedor y la recolección del domingo.

## F6 — Producto y publicación (2–3 semanas)

> **806 contenedores simulados (supuesto de 2022).** Las cifras de Suba de esta fase salen de los 806 contenedores negros simulados con la base de 2022 (`SubaBase-n807-s1`, o la semilla 7 en el plan de demo). La Suba de 2026 tiene 320 en su Plan de Contenerización, y la diferencia está medida en F7 (ADR 0013).

- [x] Docker Compose: `docker compose up` levanta los datos de OSM (de un release, verificados por SHA-256), PostGIS y la app con el mapa (ADR 0010).
- [x] Persistencia en PostgreSQL + PostGIS (ADR 0010). Se guardan las órdenes de cada turno y, al reiniciar, se repiten: 52 órdenes en 433 ms, con los mismos 53 eventos. Cada posición transmitida queda como punto de PostGIS (R08).
- [x] API REST y WebSocket estables (ADR 0011): errores RFC 9457 en español, validación en el borde, turnos en el WebSocket para ponerse al día con `GET /api/events?after=` y el contrato en `docs/api.md`, que un test compara con el código.
- [x] Dashboard completo: plan, línea de tiempo, eventos, métricas. La línea de tiempo muestra las visitas del turno por corriente, la franja de R02, los eventos y el avance (contenedores, toneladas, vehículos en la calle), contado desde los recorridos: al final del turno da 1612 de 1612 contenedores, también con un compactador averiado.
- [x] Horarios estimados de paso por sector (R04). Los sectores son las 12 UPZ de Suba, de OSM, y la zona rural, que no tiene UPZ. Cada UPZ tiene la ventana de cada corriente, en cuartos de hora: 132 pasadas de vehículo. Se descarga en CSV, y cada contenedor dice cuánto se desvía hoy de su horario publicado. Con C-04 averiado a las 18:00, 82 contenedores llegan fuera de su ventana; el que más, 73 min después.
- [x] Caso escrito en ymid.me con las métricas ([ymid.me/projects/rutea](https://ymid.me/projects/rutea), 2026-10-22): el turno nocturno de Suba sobre calles reales, con la avería de C-04 tal como la respondió la API, y las cifras del arnés.

**Criterio de cierre:** alguien clona el repo, ejecuta un comando y ve la zona piloto funcionando. **Cumplido** el 2026-10-22: un clon nuevo de `main` (7ab1c2c), con volúmenes vacíos, levanta con `docker compose up`; los datos de OSM se descargan y pasan el SHA-256, Flyway crea el esquema en PostGIS, `GET /api/plan` responde con las 15 rutas y los 1.612 contenedores, y el mapa se dibuja en http://localhost:8080. La imagen salió de la caché de Docker de esta máquina, así que el tiempo de la primera construcción no quedó medido.

## F7 — Bogotá por ASE (4–6 semanas)

Se escoge cualquier conjunto de localidades (una, cinco, las veinte) y se planifica por ASE: cada ASE con al menos una localidad elegida es una instancia CVRP independiente, con su base, su flota y sus cifras, y todas se resuelven en paralelo (ADR 0012). Nunca se resuelve un CVRP de toda la ciudad, porque ningún camión recoge fuera de su ASE.

- [x] Catálogo versionado (`ServiceAreas`, 2026-10-28): las 20 localidades con su relación de OSM y su ASE, y cada ASE con su operador y su base (nodo de OSM). El reparto y los operadores están verificados en la Alcaldía (7 feb. 2026), y la etiqueta `ref` de cada relación coincide con el número oficial de la localidad. Las bases de las ASE 1 a 4 quedan `PENDIENTE_VERIFICAR`, porque los informes de la Superservicios no se pudieron descargar de forma automática. Sumapaz entra marcada como rural.
- [x] Refactor sin cambio de comportamiento (2026-10-28): el escenario (`AreaScenario`), el planificador (`AreaPlanner`), la normativa, los sectores y la operación en vivo reciben la ASE en lugar de suponer Suba. La base y las cifras del operador pasan a la `Operation` de su ASE en `ServiceAreas`, y las de toda Bogotá quedan en `BogotaFigures`. Solo los puntos de entrada (los `main` y la API) eligen el ASE 5. `suba.rgraph`, `suba-sitios-sensibles.csv`, `suba-upz.geojson`, `SubaBase-n807-s1.vrp` y `plan-suba.json` salen con los mismos SHA-256 que antes del cambio. `AreaSpec` no necesitó varias relaciones: el grafo de Bogotá usa el límite del Distrito como área, y cada contenedor se asigna a su localidad por polígono en `rutea-app`, como ya se hace con las UPZ. `BuildRoadGraph` y los sitios sensibles ya dependían solo del grafo.
- [x] Grafo de Bogotá (2026-10-28; extracto `colombia-261019`; i5-14400F, 16 hilos, Java 21.0.12): las calles públicas de las 20 localidades y los sitios con ubicación verificada (la base de Torca y la portería de Doña Juana; las bases de las ASE 1 a 4 entran cuando se verifiquen). `AreaSpec.BOGOTA` usa el límite del Distrito (relación 1387968) como área; `mvn -pl rutea-geo compile exec:java@bogota` escribe `data/osm/bogota.rgraph`.
  - 70.728 vértices, 168.035 arcos y 7.407 km de calles. Se construye en 8,4 s y pesa 7,5 MB. Dos construcciones dan el mismo SHA-256 (`852c0533…`). La poda a la componente fuertemente conexa quita 389 de 71.117 vértices (0,5 %). El "centro de la localidad" que imprime el constructor cae en el suelo rural del sur del Distrito, así que con Bogotá entera esa cifra no sirve.
  - Suba sobre este grafo (`mvn -pl rutea-app exec:java@graphs`): los mismos 806 contenedores en los mismos nodos de OSM, y ALNS con semilla 1 y 2.000 iteraciones en los dos grafos. CSV en `docs/benchmarks/f7-bogota-graph/`.

    | Grafo | Vértices | Arcos | Matriz de 808 lugares, en frío | En caliente | Costo ALNS | Viajes |
    |---|---:|---:|---:|---:|---:|---:|
    | Suba (F3) | 18.729 | 35.364 | 87 ms | 94 ms | 1.017,5 km | 17 |
    | Bogotá | 70.728 | 168.035 | 390 ms | 410 ms | **1.006,0 km** | 17 |

    Con el grafo de Bogotá, el plan de Suba mide **1,13 % menos**. El 4,9 % de las celdas de la matriz se acorta, porque los camiones pueden cortar por calles locales de las localidades vecinas: 6,8 m menos en promedio y hasta 1.934 m menos. Otro 0,4 % sale más largo, pero a lo sumo 4 m. Es el redondeo: cada arco guarda sus metros en entero, y en Bogotá un mismo tramo queda partido en más arcos por los cruces con calles que en el grafo de Suba no entraban. Las cifras de F3 a F6 siguen siendo las del grafo de Suba.
  - Escala: la matriz de 1.702 lugares (1.700 contenedores repartidos por toda Bogotá, más la base y la portería), donde F3 previó el primer ajuste del solver, sale en 929 ms en frío y 923 ms en caliente. Con los contenedores por ASE (paso 5), la medición pasó a la ASE más grande.
- [x] Cifras por ASE en `docs/datos.md` (2026-10-28):
  - **Con fuente:** el suelo urbano de cada ASE (línea base del PGIRS, 2018), los litros de contenedores exigidos a cada una (Anexo 11 de la licitación de 2017) y el centroide de cada ASE con su distancia a Doña Juana (estudios de costos de los prestadores, en la misma línea base).
  - **Corrección:** el 15 % del ASE 5 que Rutea usa desde F3 es la parte del suelo urbano, no de las toneladas. El valor no cambia, y Suba sale con los mismos bytes, pero ahora figura como supuesto: el suelo urbano aproxima la parte de las toneladas y de los contenedores. En el código pasó de la `Operation` de Torca a `ServiceArea.urbanAreaShare`, con el valor de las cinco ASE.
  - **Con ese supuesto,** el ASE 2 es la más grande: 1.666 contenedores negros, por debajo de los ~1.700 del primer ajuste de escala de F3.
  - **Las participaciones no coinciden:** al ASE 5 le corresponde el 15 % del suelo urbano, pero el 25,8 % de los litros exigidos. Queda abierto cuál usar; cambiarla movería las cifras de Suba.
  - `PENDIENTE_VERIFICAR`: las toneladas por ASE, y la base, la flota, los turnos, la frecuencia y el cargue lateral de los operadores de las ASE 1 a 4. Están en sus informes de la Superservicios, que el portal no deja descargar de forma automática.
- [x] Contenedores por ASE completa, con semilla, y la selección solo filtra (2026-10-29). Tests: un contenedor queda en el mismo lugar, con la misma carga y el mismo id con cualquier selección, y elegir todas las localidades de una ASE reproduce la instancia de esa ASE.
  - **De qué localidad es cada calle:** el grafo de Bogotá solo sabe si una calle está en el Distrito. La localidad sale del punto medio del trazo, el mismo que usa el constructor del grafo, contra los límites de OSM de las 20 localidades (`mvn -pl rutea-app exec:java@localities`, 179 KB, bordes simplificados a 5 m; la `ref` de cada relación coincide con el catálogo). Una calle que queda fuera de todos los polígonos por la simplificación va a la localidad de borde más cercano.
  - **Suba no cambia:** cada calle local de Suba cae en el polígono de Suba, así que los cinco artefactos salen con los mismos SHA-256.
  - **Reparto en el grafo de Bogotá** (semilla 1; de 80 a 146 ms por ASE):

    | ASE | Contenedores negros | Por localidad |
    |---|---:|---|
    | 1 | 1.290 | Usaquén 302 · Chapinero 178 · Santa Fe 110 · San Cristóbal 304 · Usme 373 · La Candelaria 23 · Sumapaz 0 |
    | 2 | 1.666 | Tunjuelito 103 · Bosa 311 · Teusaquillo 151 · Los Mártires 89 · Antonio Nariño 87 · Puente Aranda 231 · Rafael Uribe Uribe 200 · Ciudad Bolívar 494 |
    | 3 | 967 | Kennedy 703 · Fontibón 264 |
    | 4 | 645 | Engativá 483 · Barrios Unidos 162 |
    | 5 | 806 | Suba 806 |

  - **Escala:** la matriz del ASE 2 (1.666 contenedores, más la base y la portería) sale en 941 ms en frío y 902 ms en caliente (`exec:java@graphs`; i5-14400F, 16 hilos). Como el ASE 2 aún no tiene base verificada, la matriz sale de la base de Suba, que solo cambia una fila.
  - **Límite conocido:** Sumapaz no lleva contenedores porque es rural, pero el suelo rural de Usme, Ciudad Bolívar, Usaquén, Chapinero, Santa Fe y San Cristóbal sí los recibe. Los límites de OSM son los de la localidad, no el perímetro urbano, así que esas localidades quedan con más contenedores de los que les tocan. `PENDIENTE_VERIFICAR`: recortar con el perímetro urbano del POT.
- [x] Inventario de fuentes por ASE en `docs/datos.md` (2026-10-29): bases, flotas, toneladas, rutas y contenedores de los cinco operadores, de los informes de supervisión de la UAESP (agosto de 2026), las inspecciones de la Superservicios (marzo de 2026) y las capas públicas de ArcGIS de los operadores. Con la prórroga de 2026, todas las ASE bajaron su capacidad nominal de contenedores negros, y lo que sale de la calle pasa a puerta a puerta. Decisión en el ADR 0013: el cargue lateral y el puerta a puerta son instancias distintas, y F7 planifica el cargue lateral.
- [x] Extracto de los operadores (ADR 0013, 2026-10-29): `mvn -pl rutea-app exec:java@operator-containers` descarga a `data/ase/` los contenedores negros "en campo" de las ASE 1, 2 y 4, con un manifiesto de URL, filtro, fecha de edición de la capa y SHA-256. No se versiona mientras la licencia de las capas siga `PENDIENTE_VERIFICAR`.
  - **Resultado:** 168 contenedores en el ASE 1 (153 de 1.100 L y 15 de 2.200 L), 990 de 2.200 L en el ASE 2 y 763 de 2.400 L en el ASE 4. No hay códigos repetidos, ningún contenedor cae fuera de su ASE y dos descargas seguidas dan los mismos bytes. Tabla y SHA-256 en `data/ase/README.md`.
- [x] Bases en el grafo (2026-10-29; extracto `colombia-261019`): las cuatro bases están mapeadas en OSM con el nombre del operador. Su nodo es donde la vía del predio sale a la calle pública, o el punto de la calle más cercano al edificio en el ASE 4 (tabla en `docs/datos.md`). `AreaSpec.BOGOTA` las lleva como sitios. La base sale de la `Operation` a la `ServiceArea`, porque las cinco están verificadas y las cifras de operación de las ASE 1 a 4 no. Un test comprueba que la base de cada ASE es un sitio del grafo. La base de Fucha queda en Ciudad Bolívar, fuera de su ASE.
  - **Resultado:** el grafo de Bogotá gana 6 vértices y 10 arcos (70.734 y 168.045), que son los nodos de las bases y sus vías de acceso. Las cuatro quedan en la componente fuertemente conexa. El plan de Suba mide lo mismo en los dos grafos (1.017.481 y 1.005.964 m), y los artefactos de Suba salen con los mismos SHA-256. La matriz del ASE 2 de `exec:java@graphs` sale ahora de la base de Tunjuelo. Sus tiempos se miden de nuevo en el paso siguiente, porque las dos corridas de hoy se hicieron con la máquina cargada.
- [x] Carga por capacidad (ADR 0013, 2026-10-29): `SideLoading` da la carga de cada contenedor, litros × 150 kg/m³ × llenado medio al recoger (`PENDIENTE_VERIFICAR`). Los planes usan el 100 %, la cota alta, y se reportan también con 50 y 75 %. Las ASE 2 y 4 no publican la frecuencia de sus contenedores: se supone 3 veces por semana y se mide también con 6. `mvn -pl rutea-app exec:java@side-loading` escribe la tabla en `docs/benchmarks/f7-side-loading/`.
  - **Resultado:** el cargue lateral cubre poco de las toneladas. Con llenado del 100 %, el 1,1 % en el ASE 1 y el 1,2 % en el ASE 3. En el ASE 2 cubre el 7,8 % con 3 veces por semana y el 15,5 % con 6. En el ASE 4, el 16,2 % y el 32,5 %. La tabla completa está en `docs/datos.md`.
  - **Contraste con el ASE 5:** el 13 % declarado no acota el llenado. Con los 320 contenedores del plan haría falta un llenado del 84 % si todos fueran de 3.200 L y del 245 % si fueran de 1.100 L, y no se publica la mezcla.
- [x] Segunda ASE (2026-10-29; `mvn -pl rutea-app exec:java@second-area`; ALNS con semilla 7 y 2.000 iteraciones, determinista; CSV en `docs/benchmarks/f7-second-area/`): el ASE 2 con sus 990 contenedores reales, cerca de Doña Juana. Es el extremo opuesto a Suba: muchos viajes cortos en lugar de pocos largos. `AreaScenario.buildReal` ubica cada contenedor en el extremo más cercano de su trazo de calle más cercano, con el código del operador y la carga por capacidad. Cada turno de Tunjuelo es una instancia, con los contenedores que su capa le asigna. Los compactadores son de 8 t (`PENDIENTE_VERIFICAR`). `ShiftScheduler` ya no se limita a 20 viajes: con más, los parte en grupos que asigna exactos por separado y reporta una cota inferior de camiones. Los artefactos de Suba salen con los mismos SHA-256.

  Con hasta 3 viajes por camión, el máximo de F4, la flota salía igual con 40 que con 90 min de descarga: en un turno de 10 h con viajes cortos limitaba el máximo, no el reloj. Al 50 % de día, los 5 camiones sumaban 31 h de 50 posibles. En Suba, los viajes largos hacen que el turno limite primero. Por eso el cargue lateral permite hasta 4 viajes por camión (`SideLoading.MAX_TRIPS_PER_TRUCK`), y el escenario de Suba sigue con 3: sus cifras y su plan de demo no cambian. Con 4, la cuenta queda así (camiones con 40 / 90 min de descarga, y la cota inferior entre paréntesis):

  | Turno | Contenedores | Llenado | t | Viajes | Camiones, 40 min | Camiones, 90 min | km, 40 min |
  |---|---:|---:|---:|---:|---:|---:|---:|
  | Día, 10 h | 609 | 50 % | 100,5 | 13 | 4 (4) | 5 (5) | 376,0 |
  | | | 75 % | 151,0 | 20 | 5 (5) | 7 (7) | 533,2 |
  | | | 100 % | 201,0 | 26 | 8 (7) | 9 (7) | 714,1 |
  | Noche, 10 h | 381 | 50 % | 62,9 | 9 | 3 (3) | 3 (3) | 241,5 |
  | | | 75 % | 94,5 | 12 | 3 (3) | 4 (4) | 307,5 |
  | | | 100 % | 125,7 | 16 | 4 (4) | 6 (6) | 400,6 |

  - **¿Alcanzan los 5 de Tunjuelo?** Solo si cada camión trabaja los dos turnos, y no con contenedores llenos. Con 40 min de descarga alcanzan hasta el 75 % de llenado (5 de día y 3 de noche). Con 90 min, solo hasta el 50 %. Al 100 % hacen falta 8 o 9 de día. Con un turno por camión no alcanzan con ningún llenado (4 + 3 = 7 en el mejor caso).
  - **La descarga en Doña Juana pesa tanto como el llenado:** al 75 %, pasar de 40 a 90 min sube la flota de día de 5 a 7 camiones.
  - **Partir en grupos:** solo hace falta con 26 viajes, de día al 100 %. Ahí el reparto da 8 y 9 camiones, y la cota inferior, 7. La cota es la mayor entre el tiempo de los viajes y el número de viajes entre 4, y con 4 viajes por camión ninguna de las dos es ajustada. En los demás casos el reparto es exacto.
  - **Escenario:** de 2,0 a 3,1 s por turno, casi todo en ubicar cada contenedor en su trazo, que recorre todos los trazos del grafo. La matriz del ASE 2 de `exec:java@graphs`, con la base de Tunjuelo, salió a 3–5 % de la medida anterior con un núcleo ocupado por el navegador. No se reemplaza la cifra publicada.
- [x] Suba de 2026 (ADR 0013, 2026-10-29): `SubaNoche-n321-s1-f100`, con los 320 contenedores del plan aprobado. Su ubicación no se publica, así que se simulan con el método de F3 y la misma semilla. Cada uno tiene un tamaño al azar entre los tres de Torca y carga por capacidad, en el turno de 18:00 a 02:00 y con hasta 4 viajes por camión (`AreaScenario.buildPlanned`). `mvn -pl rutea-app exec:java@suba-instance` escribe las dos instancias. `SubaBase-n807-s1` queda congelada, con los mismos bytes, y las cifras de F3 a F6 llevan su rótulo al comienzo de cada fase.
  - **Diferencia** (`mvn -pl rutea-app exec:java@suba-2026`; mismo grafo y misma ubicación; ALNS con semilla 1 y 2.000 iteraciones, la de F4, determinista; CSV en `docs/benchmarks/f7-suba-2026/`). La fila de los 806 reproduce las cifras de F4: 9 y 16 camiones.

    | Instancia | Contenedores | t/día | Parte de las 852 t/día supuestas | Viajes | km del solver | Camiones, 40 / 90 min |
    |---|---:|---:|---:|---:|---:|---:|
    | `SubaBase-n807-s1` (2022) | 806 | 110,1 | 12,9 % | 17 | 1.017,5 | 9 / 16 |
    | 2026, llenado del 50 % | 320 | 47,4 | 5,6 % | 7 | 492,5 | 5 / 7 |
    | 2026, llenado del 75 % | 320 | 71,0 | 8,3 % | 11 | 654,2 | 6 / 9 |
    | 2026, llenado del 100 % | 320 | 94,7 | 11,1 % | 14 | 796,6 | 7 / 8 |

    Con 320 contenedores llenos, Suba recoge por cargue lateral un 14 % menos que con los 806 de 2022, con un 22 % menos de km del solver y 2 camiones menos con 40 min de descarga. El 11,1 % queda por debajo del 13 % mínimo que declara el PPSA: o los contenedores llegan más llenos o más densos, o la mezcla de tamaños es más grande que la supuesta. Con 90 min de descarga, al 75 % hacen falta más camiones que al 100 % (9 frente a 8): ALNS minimiza km, no camiones, y sus viajes cambian con la carga. La flota como resultado (paso siguiente) lo tiene que tener en cuenta.
  - **Arnés** (60 s; semillas 1, 2 y 3; i5-14400F, 16 hilos, Java 21.0.12; 6 procesos a la vez, con el navegador abierto; CSV en `docs/benchmarks/f7-suba-2026/frio` y `caliente`):

    | Solver | En frío (prom.; mín.–máx.) | En caliente (prom.; mín.–máx.) | Viajes |
    |---|---:|---:|---:|
    | `nearest-neighbor` | 889,2 km | 889,2 km | 14 |
    | `clarke-wright` | 863,9 km | 863,9 km | 15 |
    | `local-search` | 840,6 km; 839,6–841,2 | igual | 15 |
    | `alns` | 791,5 km; 791,0–792,3 | 792,0 km; 791,8–792,3 | 14 |
    | `alns-x4` | **790,5 km**; 789,8–791,1 | **791,5 km**; 790,6–792,7 | 14 |
    | `timefold` 2.7.1, configuración por defecto | 832,7 km; 827,2–838,8 | 832,4 km; 829,5–836,9 | 14 |

    Las 36 soluciones son factibles según el `Evaluator`. Con 321 nodos, Timefold queda un 5 % por encima de `alns-x4`, frente al 35 % con los 807 de `SubaBase-n807-s1`. Entre `alns` y `alns-x4`, y entre frío y caliente, las diferencias (≤ 0,2 %) están dentro de la dispersión entre semillas.
- [x] Planificación en paralelo (2026-10-29; `mvn -pl rutea-app exec:java@city-plan`; i5-14400F, 16 hilos, Java 21.0.12, con el navegador abierto; CSV en `docs/benchmarks/f7-city-plan/`): `CityPlanner` reparte la selección entre sus ASE, y cada ASE sale con una instancia de cargue lateral por turno. Las ASE 1, 2 y 4 usan sus contenedores reales y las ASE 3 y 5 la cantidad oficial simulada. La selección filtra los reales por la localidad donde está cada uno, y los simulados igual que en el paso 5: se ubican sobre la ASE completa y solo se filtran. Cada instancia se arma y se resuelve en su propio hilo, un hilo por instancia y no por ASE, porque los turnos de una ASE son instancias independientes. El comando comprueba que el plan en paralelo sea el mismo que en serie, solución por solución. Los artefactos de Suba salen con los mismos SHA-256.
  - **Turnos nuevos** (detalle en `docs/datos.md`): el ASE 1 reparte sus contenedores entre día y noche en su capa, con las horas de sus microrrutas domiciliarias. Los del ASE 4 van todos al turno de mañana y los simulados del ASE 3 al de 06:00–14:00, los dos `PENDIENTE_VERIFICAR`.
  - **Resultado** (llenado del 100 %, ALNS con semilla 7 y 2.000 iteraciones; en frío / en caliente, la segunda pasada en la misma JVM):

    | Localidades | ASE | Instancias | Contenedores | En paralelo | En serie | Aceleración |
    |---:|---:|---:|---:|---:|---:|---:|
    | 1 (Suba) | 1 | 1 | 320 | 4,8 / 4,1 s | 4,3 / 4,1 s | 0,9 / 1,0× |
    | 5 (Usme, Ciudad Bolívar, Kennedy, Engativá, Suba) | 5 | 6 | 1.608 | 11,6 / 11,4 s | 22,0 / 21,8 s | 1,9× |
    | 20 | 5 | 7 | 2.332 | 11,7 / 11,9 s | 31,8 / 32,4 s | 2,7× |

    En paralelo, la latencia es la de la instancia más grande, el ASE 4 con sus 763 contenedores (4,1 s de escenario y 7,8 s de ALNS). Pasar de 5 a 20 localidades no la sube. La aceleración se queda en 2,7× con 16 hilos porque las 7 instancias están muy desparejas: las del ASE 1 de noche y el ASE 3 terminan en menos de 1,2 s. Con 5 localidades, el ASE 1 no tiene contenedores de noche en Usme, y ese turno sale como "sin contenedores", no como un plan vacío.
  - **Lo que pesa:** un tercio de la latencia es armar el escenario del ASE 4, casi todo en ubicar cada contenedor en su trazo más cercano, que recorre todos los trazos del grafo. Un índice espacial en `rutea-geo` lo bajaría. La latencia no incluye dibujar el plan ni revisar la normativa; con ellos (paso «Normativa por ASE»), las 20 localidades salen en 13,3 / 13,0 s.
- [x] Flota como resultado (2026-10-29; `mvn -pl rutea-app exec:java@fleet`; las 20 localidades, ALNS con semilla 7 y 2.000 iteraciones, determinista; CSV en `docs/benchmarks/f7-fleet/`): `CityPlanner.Fleet` da los camiones de cargue lateral de cada ASE y los compara con los que declara su operador. Si se pasa, el plan sale igual, con un aviso. Si los turnos de una ASE no se cruzan, un mismo camión trabaja en todos (supuesto: con otra cuadrilla), y la flota es la del turno que más pide; si se cruzan, se suman. En las cinco ASE los turnos no se cruzan.
  - **El mínimo, demostrado:** con más de 20 viajes, `ShiftScheduler` ya no se queda con el reparto por grupos. Si pasa de la cota inferior, una ramificación y poda sobre los conjuntos de viajes que caben en un turno busca uno con menos camiones. La cota también es más ajustada: solo el primer viaje de cada camión sale de la base, cada camión paga el regreso, el tope de viajes es el del conjunto más grande que cabe, y los camiones llenos solo pueden llevar viajes que aparecen en algún conjunto lleno. Los 30 repartos de la tabla (5 ASE × 3 llenados × 2 descargas) son el mínimo demostrado, es decir, iguales a su cota. Antes, el ASE 2 de día al 100 % salía con 8 y 9 camiones frente a una cota de 7. Ahora salen 7 y 9, y el 9 está demostrado. En el ASE 4 la cota antigua daba 9 por dividir entre 4 viajes, pero allí no caben ni 4 viajes en 9 h (cada uno dura unas 2½ h con la descarga) y con 90 min no caben ni 3. Con 20 viajes o menos el reparto no cambia, y los artefactos de Suba salen con los mismos SHA-256.

    | ASE | Declarada | 50 %, 40 min | 50 %, 90 min | 75 %, 40 min | 75 %, 90 min | 100 %, 40 min | 100 %, 90 min |
    |---|---:|---:|---:|---:|---:|---:|---:|
    | 1 | 3 | 1 | 1 | 1 | 1 | 1 | 2 |
    | 2 | 5 | 4 | 5 | 5 | **7** | **7** | **9** |
    | 3 | – | 1 | 1 | 1 | 2 | 1 | 2 |
    | 4 | – | 6 | 9 | 9 | 14 | 12 | 18 |
    | 5 | – | 5 | 7 | 6 | 9 | 7 | 9 |

  - **Avisos:** solo en el ASE 2, que es el único con flota declarada que no alcanza. Con 40 min de descarga, los 5 compactadores de Tunjuelo bastan hasta el 75 % de llenado, y con 90 min, hasta el 50 %. Al 100 % hacen falta 7 o 9. Arzobispo declara 3 y el plan pide 1 o 2. Las ASE 3, 4 y 5 no declaran cuántos compactadores de cargue lateral tienen, así que no hay aviso posible.
  - **Lo que no resuelve:** el mínimo es el de los viajes que dio ALNS, que minimiza km, no camiones. En Suba de 2026, con 90 min de descarga, al 75 % hacen falta 9 camiones y al 100 %, 8 (paso «Suba de 2026»). Que el solver tenga en cuenta los turnos al armar los viajes es otro problema (VRP con varios viajes por vehículo) y queda para "Después".
  - **Costo:** la búsqueda tarda a lo sumo 0,2 s. El reparto más lento sigue siendo la programación dinámica exacta con 20 viajes: 1,5 s en el ASE 2 de día al 75 %.
- [x] Normativa por ASE (2026-10-29; extracto `colombia-261019`; `mvn -pl rutea-app exec:java@city-plan`, i5-14400F, 16 hilos, Java 21.0.12, con el navegador abierto; CSV en `docs/benchmarks/f7-city-plan/`): cada instancia de `CityPlanner` sale dibujada sobre las calles, con R02, R03 y R07 revisadas parada por parada y los horarios de paso por UPZ (R04). Los artefactos de Suba salen con los mismos SHA-256.
  - **Sectores:** `mvn -pl rutea-app exec:java@sectors` extrae las UPZ de las 19 localidades urbanas en una sola lectura del extracto (2,1 s; 244 KB versionados en `sectors/`). Son 113: las 112 oficiales, y Parque Entrenubes (60), que OSM parte entre Usme y Rafael Uribe Uribe, de ASE distintas. El código de cada UPZ tiene que ser único en su ASE, porque es la clave del sector. OSM trae dos mal, La Candelaria con el 17 y Muzú con el 42, y se corrigen a 94 y 41 con las fichas locales de la Secretaría General. La UPZ Tintal Sur (87) está etiquetada `type=public_transport`, así que el lector de límites ya no filtra por `type`. La Flora, Parque Entrenubes y Monteblanco tienen varias partes y se guardan como `MultiPolygon`: un contenedor se ubica en cualquiera, pero el plan dibuja solo el borde de la más grande, porque el formato del plan (y el de `plan-suba.json`) lleva un borde por sector. Detalle en `docs/normativa.md`.
  - **Sitios sensibles:** `mvn -pl rutea-app exec:java@bogota-sensitive-sites` da 3.048 en el rectángulo del Distrito (2.087 centros educativos, 475 hospitales o clínicas, 56 centros asistenciales y 430 hoteles; 490 KB, 2,4 s). Los 644 de Suba están todos entre ellos.
  - **Resultado** (las 20 localidades, llenado del 100 %, ALNS con semilla 7 y 2.000 iteraciones; en caliente). El plan en paralelo sale igual al de en serie, también en su normativa.

    | Instancia | Turno | Contenedores | R02: de noche, con medidas contra el ruido | R03 | R04: UPZ con pasadas | R07: sin compactar | Dibujar y revisar |
    |---|---|---:|---:|---|---:|---:|---:|
    | ASE 1 | Día, 06:00 | 155 | 0 | cumple | 4 | 37 (23,9 %) | 223 ms |
    | ASE 1 | Noche, 18:00 | 13 | 0 | cumple | 5 | 2 (15,4 %) | 52 ms |
    | ASE 2 | Día, 06:00 | 609 | 0 | cumple | 5 | 81 (13,3 %) | 796 ms |
    | ASE 2 | Noche, 18:30 | 381 | 98 | cumple | 6 | 59 (15,5 %) | 501 ms |
    | ASE 3 | Día, 06:00 | 91 (simulados) | 0 | cumple | 18 | 10 (11,0 %) | 129 ms |
    | ASE 4 | Día, 06:00 | 763 | 0 | cumple | 4 | 90 (11,8 %) | 1.169 ms |
    | ASE 5 (Suba) | Noche, 18:00 | 320 (simulados) | 133 | cumple | 13 | 26 (8,1 %) | 523 ms |

  - **Lo que muestra:** los contenedores reales están concentrados. Los 763 del ASE 4 caen en solo 4 de sus 13 UPZ (Minuto de Dios, Garcés Navas, Santa Cecilia y Álamos), y los 990 del ASE 2 en 10 de sus 36. Los simulados del ASE 3 se reparten por la longitud de calle y tocan 18. En el ASE 1, uno de cada cuatro contenedores de día queda frente a un colegio, hospital o centro asistencial. El ASE 1 de noche no tiene paradas después de las 21:00: sus 13 contenedores se recogen antes.
  - **Costo:** dibujar y revisar suma hasta 1,2 s, en el ASE 4, y sube la latencia de las 20 localidades de 11,9 a 13,0 s en caliente. No se midió por separado cuánto es dibujar y cuánto revisar; la revisión recorre los 3.048 sitios en cada contenedor, y filtrarlos por la caja de la ASE es lo primero que bajaría.
  - **Pendiente para el mapa:** cada sector lleva un solo borde (el `meta` de los planes con contenedores reales se corrigió en el paso siguiente).
- [x] API y WebSocket (2026-10-29; i5-14400F, 16 hilos, Java 21.0.12): `GET /api/areas?localities=11,10` responde un `SelectionPlan` con una entrada por ASE: sus localidades elegidas, su flota frente a la declarada, los kilos por día de sus contenedores y qué parte de las toneladas de la ASE cubren, y un `DemoPlan` por turno con R02, R03, R04 y R07 revisadas. El `meta` de cada plan dice si sus contenedores son del operador o simulados, y sus supuestos de cargue lateral. Los eventos en vivo y el mensaje `shift` llevan `area`. `ApiContractTest` cubre los tipos nuevos de `docs/api.md`.
  - **Medido en la app**, primera petición después de arrancar: Suba en 6,2 s, cinco localidades en 15,8 s y las 20 en 16,3 s. Una selección ya calculada responde en 18–26 ms. El servidor guarda las últimas 8, y dos peticiones simultáneas de la misma selección hacen un solo cálculo (probado con dos `curl` a la vez: un solo plan en el log, respuestas idénticas byte a byte).
  - **gzip:** la app ahora comprime el JSON. Las 20 localidades bajan de 4,30 a 0,98 MB, y el plan de Suba de `GET /api/plan` también se comprime.
  - **Pendiente:** la operación en vivo (averías, desbordes, cierres, telemetría y sensores) sigue siendo la de Suba. Por eso los eventos llevan `area` aunque hoy siempre sea 5. `docker compose up` respondía 503 en `GET /api/areas` hasta el release de datos (paso siguiente). Las 20 localidades tardan en la app 16,3 s, frente a los 13,3 s en frío de `exec:java@city-plan`; no se midió por qué.
- [x] Mapa (2026-10-29): el modo "Ciudad" del mapa elige localidades con un clic sobre su polígono o en la lista agrupada por ASE, las planifica con `GET /api/areas` y muestra cada ASE con su flota frente a la declarada, su cobertura y sus turnos. `GET /api/localities` da los bordes y la ASE de cada localidad sin necesitar los datos de OSM. La selección queda en la dirección (`#ciudad=…`). Revisado en Chrome en escritorio y a 400 px de ancho, sin errores en la consola. Detalle en `docs/diseno.md`.
  - **Operadores sin razón social** (2026-10-29): cada uno lleva el nombre de un río de su zona: Arzobispo (ASE 1), Tunjuelo (2), Fucha (3), Salitre (4) y Torca (5). Así está en el código, la API, el mapa y los documentos. Las fuentes se siguen citando por su enlace, y algunos enlaces llevan el nombre de la empresa en su dirección. `plan-suba.json` cambió solo en la etiqueta de la base («Base de Torca · Fontibón»); el grafo, los sitios, las UPZ y las instancias `.vrp` de Suba siguen con los mismos bytes.
  - **Color por operador:** en el modo Ciudad, las rutas, la muestra y los camiones de cada ASE van en el color de su operador (`--operator-1` a `--operator-5`, en `docs/diseno.md`).
  - **Pendiente:** la ciudad no tiene operación en vivo, línea de tiempo ni horarios de paso por UPZ en el panel. El plan estático (`pnpm dev` sin la API) sigue siendo solo Suba.
- [x] Benchmarks (2026-10-29): `mvn -pl rutea-app exec:java@bogota-instances` exporta en `data/bogota/` una instancia `.vrp` por ASE y turno, las mismas que arma `CityPlanner` con las 20 localidades. Se comprobó corriendo `alns` con semilla 7 y 2.000 iteraciones, que da los mismos costos y viajes que el plan de la ciudad en las siete. El formato CVRPLIB solo tiene capacidad, así que el arnés mide los km; el turno, los viajes por camión y la descarga los agrega `CityPlanner` después. Las instancias de Suba siguen con los mismos SHA-256.
  - **Arnés** (60 s; semillas 1, 2 y 3; i5-14400F, 16 hilos, Java 21.0.13; 6 procesos a la vez, uno por solver; CSV en `docs/benchmarks/f7-areas/frio` y `caliente`). Promedio de las tres semillas, en km del solver; el detalle por semilla está en los CSV:

    | Instancia | Viajes | `nearest-neighbor` | `clarke-wright` | `local-search` | `alns` | `alns-x4` | `timefold` 2.7.1 |
    |---|---:|---:|---:|---:|---:|---:|---:|
    | ASE 1 día (155) | 4 | 192,9 | 119,5 | 119,4 | **118,8** | **118,8** | 118,9 |
    | ASE 1 noche (13) | 1 | 55,0 | **54,8** | **54,8** | **54,8** | **54,8** | **54,8** |
    | ASE 2 día (609) | 26 | 619,7 | 632,1 | 607,4 | **586,8** | 587,0 | 629,4 |
    | ASE 2 noche (381) | 16 | 329,8 | 367,9 | 341,2 | 316,5 | **316,0** | 323,1 |
    | ASE 3 día (91, simulados) | 2 | 157,9 | 134,3 | 122,9 | **119,5** | **119,5** | 120,6 |
    | ASE 4 día (763) | 35 | 1.022,9 | 1.053,4 | 1.030,2 | 991,2 | **990,3** | 1.104,0 |
    | ASE 5 noche (320, simulados) | 14 | 862,3 | 798,3 | 789,1 | **772,6** | 773,0 | 803,6 |

    En caliente (`--warmup`), los tres primeros son deterministas y dan lo mismo. Los demás:

    | Instancia | `alns` | `alns-x4` | `timefold` 2.7.1 |
    |---|---:|---:|---:|
    | ASE 1 día | **118,8** | **118,8** | 118,9 |
    | ASE 1 noche | **54,8** | **54,8** | **54,8** |
    | ASE 2 día | 587,3 | **587,0** | 635,4 |
    | ASE 2 noche | 317,0 | **315,6** | 324,6 |
    | ASE 3 día | **119,5** | **119,5** | 120,6 |
    | ASE 4 día | 991,4 | **990,3** | 1.119,5 |
    | ASE 5 noche | **773,2** | **773,2** | 809,1 |

  - **Lo que muestra:** las 252 soluciones son factibles según el `Evaluator`. `alns` y `alns-x4` quedan a menos de 0,5 % entre sí, dentro de la dispersión entre semillas, y usan en todas el mínimo posible de viajes (los kilos entre 8.000, redondeado hacia arriba). Timefold, con su configuración por defecto, empata en las instancias pequeñas, pero queda un 11,5 % por encima de `alns-x4` en el ASE 4 (13,0 % en caliente) y un 7,2 % en el ASE 2 de día. La distancia crece en general con el tamaño, como en Suba. Clarke-Wright usa 2 o 3 viajes de más en las ASE 2 y 4. `local-search` arranca de Clarke-Wright y en el ASE 2 de noche y el ASE 4 termina por encima del vecino más cercano, porque quita algunos de esos viajes pero no todos.
- [x] Release de datos de Bogotá (2026-10-29): [`data-bogota-20261029`](https://github.com/YmidOrtega/Rutea/releases/tag/data-bogota-20261029) lleva el grafo de Bogotá, sus sitios sensibles, los contenedores de las ASE 1, 2 y 4 y `fuentes.csv`. `docker compose up` los descarga junto con los de Suba y los verifica contra `data/bogota/SHA256SUMS`, que está en el repo. `docker/fetch-data.sh` revisa archivo por archivo, y solo descarga lo que falta o no verifica. El grafo y los sitios se reconstruyen desde el extracto con los mismos bytes.
  - **Probado** con volúmenes vacíos (proyecto de Compose aparte): se descargan los seis archivos y pasan el SHA-256. Una segunda corrida no descarga nada, y un CSV alterado se vuelve a descargar solo. Las 19 localidades urbanas responden en 16,7 s (4,30 MB), con las cinco ASE.
  - **Licencia:** los contenedores van como copia fechada de las capas públicas de los operadores, que no declaran licencia (`PENDIENTE_VERIFICAR`). Se citan por su URL en `fuentes.csv`.

**Criterio de cierre:** con un comando se planifica cualquier selección de localidades (una, cinco o las veinte), y cada ASE involucrada sale con un plan factible según el `Evaluator`, cifras con fuente o marcadas `PENDIENTE_VERIFICAR` y su fila en la tabla del arnés. Cada plan dice qué parte de las toneladas de su ASE cubre el cargue lateral. El refactor deja Suba con los mismos bytes, y su diferencia sobre el grafo de Bogotá queda medida y publicada. **Cumplido** el 2026-10-29:
- **Un comando:** con `docker compose up` y volúmenes vacíos, `GET /api/areas` planifica cualquier selección. En la app se midieron Suba (6,2 s), cinco localidades (15,8 s) y las veinte (16,3 s); en Docker, las 19 urbanas (16,7 s). Cada selección sale por ASE.
- **Factibles:** las siete instancias de las cinco ASE son las mismas que resuelve `CityPlanner`, y las 259 soluciones medidas sobre ellas (252 del arnés y 7 de la comprobación con semilla 7) son factibles según el `Evaluator`.
- **Tabla del arnés:** cada ASE tiene su fila, en frío y en caliente.
- **Toneladas:** cada plan dice qué parte de las toneladas de su ASE cubre. Las ASE 1 a 4 dan la cifra con su fuente. El ASE 5 dice «sin dato», porque su operador no publica sus toneladas, y no se inventa la cifra.
- **Suba:** el grafo, los sitios, las UPZ y las instancias `.vrp` de Suba conservan sus SHA-256. `plan-suba.json` cambió solo en la etiqueta de la base, al quitar los nombres de los operadores. La diferencia sobre el grafo de Bogotá está en `docs/benchmarks/f7-bogota-graph/`.

## Más adelante (sin fase asignada)

- **Modelo de recolección por localidad.** Una localidad con contenedores (CVRP: el camión visita puntos) y otra casa por casa (CARP: el camión recorre calles completas recogiendo a lado y lado). El casa por casa se transforma en un CVRP equivalente, con cada tramo de calle con demanda como cliente, para reutilizar el solver y el evaluador. Verificado en 2026 (ADR 0013): todas las ASE combinan los dos esquemas, y en el ASE 3 casi todo es puerta a puerta. El modelo va en su propio ADR, después de medir cuántos nodos daría el ASE más grande.

## Después (fuera de alcance por ahora)

- Tráfico en tiempo real de un proveedor externo.
- App móvil para conductores.
- Integración con sensores IoT reales.
- Facturación, usuarios y roles.
- Viajes pensados para los turnos (VRP con varios viajes por vehículo): que el solver arme los viajes minimizando camiones y no solo km. Hoy la flota es el mínimo para los viajes que da ALNS (F7, «Flota como resultado»).
