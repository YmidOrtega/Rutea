# Rutea — guía para agentes y colaboradores

Este archivo es la fuente de verdad para cualquier agente de código (Claude Code, Codex, Cursor, etc.) y para personas que trabajen en el repo. `CLAUDE.md` lo importa.

## Qué es Rutea

Planificador de **microrrutas de recolección de residuos sólidos** para una zona real de Bogotá (piloto: localidad de Suba). Decide qué vehículo recoge qué contenedores y en qué orden, respetando capacidad, turnos, tipo de residuo y la normativa colombiana del servicio público de aseo. Cuando algo cambia en la operación (avería, vía cerrada, contenedor desbordado), replanifica en caliente.

El corazón del proyecto es un **solver CVRP propio** (Capacitated Vehicle Routing Problem), medido contra instancias públicas de CVRPLIB. Todo lo demás (grafo vial, dominio, simulación, API, mapa) existe para que ese solver trabaje sobre una ciudad real y se vea funcionando.

Autor: Yamid Ortega (ingeniero ambiental y desarrollador backend). El proyecto une las dos carreras: el conocimiento del dominio y la normativa es una ventaja que el código debe reflejar.

## Principios que no se negocian

1. **Rutas eficientes y demostrables.** Toda afirmación de rendimiento sale de una corrida del arnés de benchmarks, con instancia, semilla, límite de tiempo y máquina. Nunca se inventan ni se estiman cifras.
2. **Normativa trazable.** Cada restricción regulatoria del código cita su fuente (`NormRef`: norma + artículo + URL) y aparece en `docs/normativa.md`. Si una regla no está verificada contra el texto oficial, se marca como `PENDIENTE_VERIFICAR` y no se presenta como hecho.
3. **El solver es Java puro.** `rutea-solver` no depende de nada (ni Spring, ni librerías de optimización). Solo recibe números: matriz de distancias, demandas, capacidades.
4. **Visual moderna.** La interfaz es parte del producto, no un extra. Se sigue `docs/diseno.md`.
5. **Alcance por fases.** Se trabaja la fase actual de `docs/roadmap.md` hasta cumplir su criterio de cierre. Si algo no mejora el solver ni lo pone a prueba, va a la sección "Después" del roadmap.

## Estructura del repo

Estructura objetivo. Los módulos se crean a medida que avanzan las fases; hoy solo existen `docs/` y `data/`.

```
rutea/
├── AGENTS.md / CLAUDE.md     guías para agentes
├── pom.xml                   parent Maven (Java 21, BOM de Spring Boot)
├── rutea-solver/             CVRP: modelo, lector CVRPLIB, evaluador, solvers, benchmarks (Java puro)
├── rutea-domain/             dominio de residuos: corrientes, vehículos, contenedores, reglas normativas
├── rutea-geo/                grafo vial desde OpenStreetMap, Dijkstra/A*, matriz de distancias (F3)
├── rutea-sim/                reloj de simulación, sensores de llenado, eventos de operación (F5)
├── rutea-app/                Spring Boot: API REST, WebSocket, persistencia; une todos los módulos
├── web/                      frontend React + TypeScript + MapLibre + deck.gl (Vite)
├── data/cvrplib/             instancias X de Uchoa et al. con sus BKS (ver data/cvrplib/README.md)
└── docs/                     normativa, arquitectura, roadmap, diseño, ADRs
```

### Dirección de dependencias (obligatoria)

```
rutea-solver   ← no depende de nadie
rutea-geo      ← no depende de nadie
rutea-domain   ← no depende de nadie
rutea-sim      → rutea-domain
rutea-app      → todos
```

La traducción "problema de residuos → instancia CVRP" vive en `rutea-app` (paquete `planning`), nunca dentro del solver.

## Comandos

Comandos previstos; se activan cuando exista cada módulo. Actualiza esta sección si cambian.

```bash
# Backend
mvn verify                                   # compila y corre todos los tests
mvn -pl rutea-solver test                    # solo el solver
mvn -pl rutea-solver exec:java \
  -Dexec.args="../data/cvrplib/X"            # arnés de benchmarks → rutea-solver/target/benchmarks/*.csv
mvn -pl rutea-app spring-boot:run            # API en http://localhost:8080

# Frontend
cd web && npm install
npm run dev                                  # http://localhost:5173 (proxy /api → :8080)
npm run build
npm run demo:data                            # regenera web/public/demo/plan-suba.json
```

## Convenciones de código (Java)

- Java 21. Usa `record` para datos inmutables, `sealed` para jerarquías cerradas, `switch` con patrones.
- Sin Lombok. Sin `null` en APIs públicas: usa `Optional` o valida con `Objects.requireNonNull`.
- Identificadores en inglés; Javadoc en español cuando explica el dominio. Los términos normativos se documentan con su nombre oficial (p. ej. "microrruta", "ECA", "recicladores de oficio").
- Paquete base: `me.ymid.rutea`.
- Distancias en el solver: enteros (`int`), redondeo TSPLIB `nint`, igual que CVRPLIB. Nunca `double` en la función objetivo.
- Índices en el solver: el depósito es el nodo `0`; los clientes van de `1` a `n-1`.
- Aleatoriedad siempre con semilla explícita (`SplittableRandom(seed)`), para que cada corrida sea reproducible.
- En código caliente del solver: arreglos primitivos, sin boxing, sin streams, sin reservar memoria dentro de los bucles de búsqueda local.
- Concurrencia: virtual threads para I/O; para búsquedas paralelas del solver, un hilo por semilla y sin estado compartido mutable.

## Tests

- JUnit (versión gestionada por el BOM de Spring Boot) + AssertJ.
- Todo solver nuevo debe pasar `SolverContractTest`: soluciones factibles (capacidad, cada cliente exactamente una vez) en todas las instancias de `data/cvrplib`.
- El `Evaluator` es la única fuente de verdad del costo. Sus tests comprueban que reproduce el costo publicado de cada BKS.
- Toda regla normativa del dominio tiene un test que la cite en el nombre o en `@DisplayName`.

## Benchmarks

- Instancias: set X de Uchoa et al. (2017), CVRPLIB. Métrica principal: **gap** = (costo − BKS) / BKS.
- El arnés escribe un CSV por corrida: instancia, solver, semilla, límite de tiempo, costo, BKS, gap, tiempo real, vehículos usados.
- Antes de publicar un número en README o en ymid.me, se corre en frío y en caliente y se reportan ambos.

## Normativa (resumen; el detalle está en docs/normativa.md)

- Decreto 1077 de 2015, Título 2 (compila el Decreto 2981 de 2013): macrorrutas y microrrutas, horarios, frecuencias, vehículos.
  - No aprovechables: frecuencia mínima de **2 veces por semana**.
  - Recolección entre **21:00 y 06:00** en zonas residenciales, hoteles u hospitales: requiere medidas de mitigación de ruido.
  - Avería de un vehículo: el servicio se restablece en máximo **3 horas**.
  - Cambios de ruta u horario: aviso con **3 días** de anticipación, salvo emergencias.
  - Municipios con más de 5.000 usuarios: vehículos con **caja compactadora** cerrada (excepto recolección selectiva de aprovechables y RCD).
  - Cerca de centros educativos, hospitales y centros de salud no se compacta.
- Decreto 596 de 2016: la actividad de aprovechamiento la prestan organizaciones de recicladores de oficio. En Rutea, las rutas de aprovechables son selectivas y terminan en una ECA, no en el relleno.
- Resolución 2184 de 2019: código de colores. **Blanco** aprovechables, **verde** orgánicos aprovechables, **negro** no aprovechables. Rutea usa este código como semántica visual.
- Bogotá: PGIRS adoptado por Decreto 345 de 2020, hoy en el Título 12 del Decreto 653 de 2025, modificado por el Decreto 045 de 2026. Contratos de aseo prorrogados hasta 2028 (Resolución CRA 1027 de 2026, según prensa: verificar).

Si modificas o agregas una regla: actualiza `docs/normativa.md`, el `NormRef` en código y su test en el mismo cambio.

## Frontend

- React + TypeScript + Vite. Mapa con MapLibre GL; capas de datos con deck.gl (`PathLayer`, `TripsLayer`, `ScatterplotLayer`).
- Diseño oscuro primero, con tokens en `web/src/styles/tokens.css`. No se escriben colores literales en componentes.
- Los colores de las corrientes de residuo siguen la Resolución 2184 de 2019 (adaptados para verse sobre un mapa oscuro; ver `docs/diseno.md`).
- Cifras con `font-variant-numeric: tabular-nums`. Respeta `prefers-reduced-motion` (sin animación de camiones por defecto en ese caso).
- Mientras no exista el grafo vial real (F3), los datos de demo se trazan sobre una cuadrícula simulada. La UI lo dice explícitamente; nunca se presentan como calles reales.

## Flujo de trabajo

- Ramas: `feat/…`, `fix/…`, `docs/…`, `perf/…`. Commits en inglés, estilo Conventional Commits.
- Un cambio de rendimiento del solver incluye en la descripción del PR la tabla del arnés antes y después.
- Decisiones de arquitectura: un ADR nuevo en `docs/adr/` (plantilla en `docs/adr/0000-plantilla.md`).
- Si no sabes algo del dominio o de la norma, pregunta o marca `PENDIENTE_VERIFICAR`. No inventes artículos.

## Fase actual

**F0 — Fundamentos.** Ver `docs/roadmap.md` para el estado y el criterio de cierre.
