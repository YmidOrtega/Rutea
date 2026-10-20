# Roadmap

Cada fase cierra con un criterio medible. No se empieza la siguiente sin cumplirlo, salvo que se registre la excepción aquí.

Duraciones orientativas, trabajando unas 10 horas por semana.

| Fase | Nombre | Estado |
|---|---|---|
| F0 | Fundamentos | Completa |
| F1 | Primer solver | **Siguiente** |
| F2 | Metaheurística y paralelismo | Pendiente |
| F3 | Mapa real | Pendiente |
| F4 | Restricciones del dominio | Pendiente |
| F5 | Operación en tiempo real | Pendiente |
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

## F2 — Metaheurística y paralelismo (3–4 semanas)

- ALNS: destrucción (aleatoria, por relación de Shaw, peor costo, por cadena) y reparación (voraz, regret-k).
- Pesos adaptativos de operadores y criterio de aceptación (recocido simulado o record-to-record).
- Varias búsquedas en paralelo con semillas distintas; se queda la mejor.
- Microbenchmarks con JMH de la evaluación de movimientos.
- Comparación contra Timefold con el mismo límite de tiempo (solo en el arnés, nunca como dependencia del solver).

**Criterio de cierre:** gap promedio de 2–3 % en instancias X de hasta ~300 clientes con un límite de 60 s, y tabla comparativa publicada.

## F3 — Mapa real (2–3 semanas)

- Extracto OSM de Bogotá recortado a la zona piloto (Suba).
- Grafo dirigido compacto (sentidos viales, velocidades por tipo de vía).
- Dijkstra paralelo desde cada contenedor para construir la matriz; A* para consultas puntuales.
- Recalcular solo lo afectado cuando se cierra una vía.
- El frontend reemplaza la cuadrícula simulada por las geometrías reales.

**Criterio de cierre:** el solver planifica la zona piloto y las rutas se dibujan sobre calles reales.

## F4 — Restricciones del dominio (3 semanas)

- Ventanas horarias y turnos (R02).
- Varios viajes por turno: ruta → descarga → ruta.
- Flota heterogénea: compactadores para no aprovechables, vehículos de carga para aprovechables (R06, R10).
- Frecuencias semanales por zona (R03).
- Paradas sin compactación (R07).
- Reporte por ruta: km, horas, toneladas, CO₂ estimado a partir del consumo de diésel.

**Criterio de cierre:** comparación contra un barrido ingenuo por cuadrantes en la zona piloto, con ahorro en km y CO₂.

## F5 — Operación en tiempo real (3 semanas)

- Reloj de simulación acelerable.
- Sensores de llenado simulados, con patrones por zona y día.
- Eventos: avería (R05), vía cerrada, contenedor desbordado.
- Replanificación incremental: se congela lo recorrido y ALNS arranca desde el plan vigente con límite de tiempo.
- Posiciones y eventos por WebSocket (R08).

**Criterio de cierre:** una avería produce un plan nuevo con latencia P99 medida y reportada.

## F6 — Producto y publicación (2–3 semanas)

- API REST y WebSocket estables, persistencia en PostgreSQL + PostGIS.
- Dashboard completo: plan, línea de tiempo, eventos, métricas.
- Horarios estimados de paso por sector (R04).
- Docker Compose: un comando para levantar todo.
- Caso escrito en ymid.me con las métricas.

## Más adelante (sin fase asignada)

- **Modelo de recolección por localidad.** Una localidad con contenedores (CVRP: el camión visita puntos) y otra casa por casa (CARP: el camión recorre calles completas recogiendo a lado y lado). El casa por casa se transforma en un CVRP equivalente, con cada tramo de calle con demanda como cliente, para reutilizar el solver y el evaluador. Pendiente de verificar con la UAESP y el operador de cada zona qué esquema aplica hoy en Bogotá.

**Criterio de cierre:** alguien clona el repo, ejecuta un comando y ve la zona piloto funcionando.

## Después (fuera de alcance por ahora)

- Toda Bogotá o varias localidades a la vez.
- Tráfico en tiempo real de un proveedor externo.
- App móvil para conductores.
- Integración con sensores IoT reales.
- Facturación, usuarios y roles.
