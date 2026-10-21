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
| F5 | Operación en tiempo real | **Siguiente** |
| F6 | Producto y publicación | Pendiente |

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

Pendiente de F2: correr en frío y en caliente antes de publicar estas cifras fuera del repo, y comparar contra Timefold afinado (aquí va con su configuración por defecto).

## F3 — Mapa real: Suba completa (3–4 semanas)

Decidido el 2026-10-20: el objetivo deja de ser la solución perfecta sobre instancias de prueba y pasa a ser una solución que funcione en el mundo real, para tomar decisiones con base en ella. Por eso F3 cubre toda la localidad y adelanta de F4 los viajes de descarga.

- Contenedores **simulados realistas** en toda Suba: unos 1.700, sobre calles residenciales reales y con densidad según la vivienda. La cifra sale de repartir por población los 10.746 contenedores que tenía Bogotá en 2022. Se reemplazan cuando aparezca un inventario oficial (UAESP o Área Limpia, operador del ASE 5).
  - Fuentes verificadas (2026-10-20): la UAESP cita "los 10.746 contenedores con los que contaba la ciudad" ([Bogotá.gov.co, 9 de marzo de 2022](https://bogota.gov.co/mi-ciudad/habitat/contenedores-soterrados-mejoraran-disposicion-de-residuos-en-bogota)). Según la UAESP en 2025, la licitación exigió contenerizar "más de 12,5 millones de litros": "más de 10.700 contenedores", "la mitad para residuos no aprovechables y la mitad para promover el reciclaje", y después "se pidieron 490 contenedores más" ([Bogotá.gov.co, 26 de febrero de 2025](https://bogota.gov.co/mi-ciudad/habitat/contenedores-de-basuras-en-bogota-apoyan-prestacion-servicio-aseo-2025)).
  - Consecuencia: solo la mitad negra (no aprovechables) va a Doña Juana. La blanca es ruta selectiva hacia una ECA (Decreto 596 de 2016).
  - El reparto por población quedó reemplazado por el 15 % del ASE 5 en las toneladas de la ciudad (PGIRS): 10.746 × 15 % × 1/2 = 806 contenedores negros. Cifras y fuentes en `docs/datos.md`.
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
- [x] Sitios ancla en el grafo: la base de Área Limpia en Fontibón (Av. Calle 17 × Carrera 124) y la portería noreste de Doña Juana (nodo `barrier=swing_gate` sobre el borde del relleno), con sus vías privadas de acceso. El lector PBF lee ahora las etiquetas de nodo y coincide con libosmium (639.874 nodos con etiquetas). Con los sitios, el grafo tiene 18.729 vértices, 35.364 arcos y 2.287 km. Centro de Suba → portería: 30,6 km de ida y 32,9 km de vuelta. Base → centro de Suba: 18,7 km.
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
  | Barrido, cuadrantes de 1 km | 17 | 1.783,9 | 95,4 | 3,82 | 0 |
  | **Barrido, cuadrantes de 2 km (mejor)** | 17 | 1.775,3 | 95,3 | 3,80 | 0 |
  | Barrido, cuadrantes de 4 km | 17 | 1.858,6 | 97,5 | 3,98 | 1 |
  | **Rutea (`alns`)** | 17 | **1.410,9** | **81,5** | **3,02** | 0 |

  Frente al mejor barrido: **20,5 % menos km, 14,5 % menos horas y 20,5 % menos CO₂**, unas 0,78 t de CO₂ menos por día de recolección con el consumo supuesto. Las horas bajan menos que los km porque las paradas y las descargas son iguales en los dos planes. Si además se encadenan viajes (abajo), Rutea baja a 1.326,5 km: 25,3 % menos que el mejor barrido. El barrido es una línea base ingenua, no las microrrutas reales de Área Limpia, que no son públicas.
- [x] Varios viajes por turno (`ShiftScheduler`): los viajes de ALNS se reparten entre camiones con programación dinámica exacta sobre los subconjuntos de viajes. Minimiza primero los camiones y después los km, con hasta 3 viajes por camión en un turno de 8 h que cuenta paradas, descargas y regreso. Un viaje que ni solo cabe queda marcado como horas extra. Para que distancia y tiempo hablen del mismo recorrido, `TravelMatrix` ahora da los segundos del camino más corto en metros, no los del más rápido (y hace la mitad de los Dijkstra).

  Los 17 viajes de ALNS (`SubaBase-n807-s1`, semilla 1, 2.000 iteraciones), con la descarga en los dos extremos del rango publicado:

  | Reparto | Camiones | km | Horas de camión | Último regreso |
  |---|---:|---:|---:|---:|
  | Un viaje por camión | 17 | 1.410,9 | 81,5 | 23:54 |
  | Mínimo de camiones, descarga de 40 min | **9** (8 con 2 viajes) | **1.326,5** | **64,6** | 01:56 |
  | Mínimo de camiones, descarga de 90 min | 16 (1 con 2 viajes) | 1.398,7 | 81,1 | 01:47 |

  Encadenar viajes ahorra km, porque el segundo viaje sale de la portería y se ahorra la salida desde la base y un regreso. Y el tiempo de descarga en Doña Juana define el tamaño de la flota: con 40 min bastan 9 camiones; con 90, hacen falta 16. En el barrido ingenuo, algunos viajes ni solos caben en el turno (cuadrantes de 500 m y de 4 km).
- [x] Plan de demo con camiones de varios viajes (semilla 7, descarga de 40 min): 9 camiones para 17 viajes, 1.310,6 km y turnos de 5:14 a 7:50 h.
- [x] Normativa en el plan (`Compliance`): R02, R03 y R07, verificadas en el texto oficial, se revisan sobre cada parada con la hora de paso del planificador y 644 sitios sensibles de OSM. Plan de demo: **79 contenedores sin compactación** (R07, a 50 m o menos de un colegio o centro de salud), **335 paradas nocturnas** que requieren medidas contra el ruido (R02) y frecuencia de 6 veces por semana, que cumple el mínimo de 2 (R03). Con salida a las 18:00, 383 de las 806 paradas caen entre las 21:00 y las 06:00. Con las mismas horas de paso y salida a las 06:00, ninguna: irían de las 06:22 a las 11:53.
- [ ] Calendario semanal por zona (R03, VRP periódico) y efecto de R07 sobre la capacidad.
- [x] Flota heterogénea (R06, R10), con supuestos. R10 se reverificó: el capítulo que el Decreto 596 de 2016 agregó al Decreto 1077 aparece derogado y lo sustituyó el Decreto 1381 de 2024 (exclusividad de las organizaciones de recicladores por 15 años y transporte selectivo hasta la ECA). Los aprovechables van en vehículos sin compactación de una organización de recicladores, que salen de una ECA y vuelven a ella; R07 no aplica a esos vehículos. Plan de demo: 9 compactadores (17 viajes, 1.310,6 km) y 6 vehículos de aprovechables (17 viajes, 429,9 km, 24,7 t). Las cifras de aprovechables descansan en supuestos sin dato público (ECA, toneladas, vehículos; ver `docs/datos.md`) y el panel los lista.
- [ ] Datos de aprovechables: inventario de ECA de la UAESP, toneladas por organización y vehículos.

## F5 — Operación en tiempo real (3 semanas)

- Reloj de simulación acelerable.
- Sensores de llenado simulados, con patrones por zona y día.
- Eventos: avería (R05), vía cerrada, contenedor desbordado.
- Replanificación incremental: se congela lo recorrido y ALNS arranca desde el plan vigente con límite de tiempo.
- Posiciones y eventos por WebSocket (R08).

**Criterio de cierre:** una avería produce un plan nuevo con latencia P99 medida y reportada. **Cumplido** el 2026-10-20: P99 de 144,3 ms.

**Avance** (2026-10-20; i5-14400F, 16 hilos, Java 21.0.12):

- [x] Replanificación ante averías (ADR 0005, R05). Se congela lo recorrido; los contenedores que el camión averiado no alcanzó a recoger los cubren camiones de reserva desde la base. ALNS arranca en caliente desde el plan vigente (`AlnsSolver.solveFrom`; los costos del arnés en el set X son idénticos antes y después) con 300 iteraciones.
- [x] Latencia medida (`mvn -pl rutea-app exec:java@breakdowns`): 500 averías con camión y hora al azar (semilla 1) sobre el plan de 9 camiones de Suba, más 20 de calentamiento. CSV en `docs/benchmarks/f5-breakdowns/`.

  | Medida | P50 | P95 | P99 | Máx. |
  |---|---:|---:|---:|---:|
  | Latencia, arranque en caliente | 43,7 ms | 122,2 ms | **144,3 ms** | 150,4 ms |
  | Latencia, ALNS desde cero | 48,3 ms | 134,2 ms | 146,7 ms | 153,2 ms |
  | Contenedores huérfanos | 42 | 96 | 101 | 103 |
  | Avería → reserva en el primer huérfano | 39,9 min | 53,0 min | 67,3 min | 73,4 min |

  En frío (la primera avería, con la JVM sin calentar): 54,4 ms. R05 se cumple en las 500 averías: la reserva llega en 73 min en el peor caso, con un despacho supuesto de 15 min (`PENDIENTE_VERIFICAR`). Con 300 iteraciones, arrancar en caliente o desde cero da casi lo mismo (+0,05 % de km): en problemas de este tamaño ALNS converge rápido de cualquier forma. Lo que gana el arranque en caliente es que el plan nuevo conserva el orden que ya conocían los conductores.
- [x] Operación en vivo (R05, R08). `rutea-app` sirve el plan (`GET /api/plan`, armado en segundo plano al arrancar), simula averías (`POST /api/breakdowns`) y las difunde por WebSocket (`/ws/events`) a todos los clientes; `GET /api/events` da el historial a quien llega tarde. En el mapa, cada compactador tiene un botón para averiarlo a la hora del reloj: el camión se detiene, sus contenedores huérfanos se marcan en rojo, la reserva aparece en el acento y el panel registra el evento con su latencia y R05. Sin la API, el mapa usa el plan estático y no ofrece averías.
- [ ] Reasignar huérfanos a camiones en servicio con margen, frente a la reserva.
- [ ] Más eventos (vía cerrada, contenedor desbordado), posiciones transmitidas por el servidor y sensores de llenado.

## F6 — Producto y publicación (2–3 semanas)

- API REST y WebSocket estables, persistencia en PostgreSQL + PostGIS.
- Dashboard completo: plan, línea de tiempo, eventos, métricas.
- Horarios estimados de paso por sector (R04).
- Docker Compose: un comando para levantar todo.
- Caso escrito en ymid.me con las métricas.

**Criterio de cierre:** alguien clona el repo, ejecuta un comando y ve la zona piloto funcionando.

## Más adelante (sin fase asignada)

- **Modelo de recolección por localidad.** Una localidad con contenedores (CVRP: el camión visita puntos) y otra casa por casa (CARP: el camión recorre calles completas recogiendo a lado y lado). El casa por casa se transforma en un CVRP equivalente, con cada tramo de calle con demanda como cliente, para reutilizar el solver y el evaluador. Pendiente de verificar con la UAESP y el operador de cada zona qué esquema aplica hoy en Bogotá.

## Después (fuera de alcance por ahora)

- Toda Bogotá o varias localidades a la vez.
- Tráfico en tiempo real de un proveedor externo.
- App móvil para conductores.
- Integración con sensores IoT reales.
- Facturación, usuarios y roles.
