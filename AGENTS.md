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

Estructura objetivo. Los módulos se crean a medida que avanzan las fases; hoy existen `docs/`, `data/`, `rutea-solver/`, `rutea-domain/`, `rutea-geo/`, `rutea-app/`, `rutea-bench/` y `web/`.

```
rutea/
├── AGENTS.md / CLAUDE.md     guías para agentes
├── pom.xml                   parent Maven (Java 21, BOM de Spring Boot)
├── rutea-solver/             CVRP: modelo, lector CVRPLIB, evaluador, solvers, benchmarks (Java puro)
├── rutea-domain/             dominio de residuos: corrientes, vehículos, contenedores, reglas normativas
├── rutea-geo/                grafo vial desde OpenStreetMap, Dijkstra/A*, matriz de distancias (F3)
├── rutea-sim/                reloj de simulación, sensores de llenado, eventos de operación (F5)
├── rutea-app/                Spring Boot: API REST, WebSocket, persistencia; une todos los módulos
├── rutea-bench/              comparación contra Timefold y microbenchmarks JMH; nunca entra al solver
├── web/                      frontend React + TypeScript + MapLibre + deck.gl (Vite)
├── data/cvrplib/             instancias X de Uchoa et al. con sus BKS (ver data/cvrplib/README.md)
├── data/osm/                 extracto de OpenStreetMap fijo, se descarga (ver data/osm/README.md)
├── data/suba/                instancias CVRP reales de Suba, se generan (ver data/suba/README.md)
└── docs/                     normativa, arquitectura, roadmap, diseño, ADRs
```

### Dirección de dependencias (obligatoria)

```
rutea-solver   ← no depende de nadie
rutea-geo      ← no depende de nadie
rutea-domain   ← no depende de nadie
rutea-sim      → rutea-domain
rutea-app      → todos
rutea-bench    → rutea-solver (Timefold y JMH viven solo aquí)
```

La traducción "problema de residuos → instancia CVRP" vive en `rutea-app` (paquete `planning`), nunca dentro del solver.

## Comandos

Comandos previstos; se activan cuando exista cada módulo. Actualiza esta sección si cambian.

```bash
# Backend
mvn verify                                   # compila y corre todos los tests
mvn -pl rutea-solver test                    # solo el solver
mvn -pl rutea-solver compile exec:java      # arnés de benchmarks → rutea-solver/target/benchmarks/*.csv
                                             # opciones: -Dexec.args="<dir> --solver <n> --seed <n>[,<n>...] --time-limit <s> --iterations <n> --warmup --out <dir>"
mvn -pl rutea-bench -am compile exec:java@compare   # el mismo arnés con Timefold disponible (--solver timefold)
mvn -pl rutea-bench -am compile exec:exec@jmh       # microbenchmarks JMH de evaluación de movimientos
mvn -pl rutea-geo compile exec:java          # grafo vial de Suba → data/osm/suba.rgraph (requiere el extracto; ver data/osm/README.md)
mvn -pl rutea-app exec:java@suba-instance    # instancia real de Suba → data/suba/SubaBase-n807-s1.vrp (ver data/suba/README.md)
mvn -pl rutea-app exec:java@baseline         # Rutea frente al barrido por cuadrantes → docs/benchmarks/f4-baseline/*.csv
mvn -pl rutea-app exec:java@sensitive-sites  # colegios, hospitales y hoteles de OSM (R02, R07) → data/osm/suba-sitios-sensibles.csv
mvn -pl rutea-app exec:java@breakdowns       # 500 averías simuladas y latencia de replanificar → docs/benchmarks/f5-breakdowns/*.csv
mvn -pl rutea-app spring-boot:run            # API en http://localhost:8080

# Frontend
cd web && pnpm install
pnpm dev                                     # http://localhost:5173 (proxy /api → :8080)
pnpm build
pnpm demo:data                               # regenera web/public/demo/plan-suba.json
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
- Instancias de Suba (`data/suba`): reales y sin BKS publicada. El arnés deja la BKS y el gap vacíos y compara los costos entre solvers; nunca se inventa una BKS.
- El arnés escribe un CSV por corrida: instancia, solver, semilla, límite de tiempo, límite de iteraciones, costo, BKS, gap, tiempo real, vehículos usados y si fue en caliente (`--warmup`).
- Con `--iterations` la corrida es reproducible en cualquier máquina; con solo `--time-limit`, el resultado depende de la velocidad de la máquina.
- Antes de publicar un número en README o en ymid.me, se corre en frío y en caliente y se reportan ambos.

## Normativa (resumen; el detalle está en docs/normativa.md)

- Decreto 1077 de 2015, Título 2 (compila el Decreto 2981 de 2013): macrorrutas y microrrutas, horarios, frecuencias, vehículos.
  - No aprovechables: frecuencia mínima de **2 veces por semana**.
  - Recolección entre **21:00 y 06:00** en zonas residenciales, hoteles, hospitales, clínicas y centros asistenciales: requiere medidas para mitigar el ruido de la recolección y la compactación.
  - Avería de un vehículo: el servicio se restablece en máximo **3 horas**.
  - Cambios de ruta, horario o frecuencia: aviso con **3 días** de anticipación, salvo caso fortuito o fuerza mayor.
  - Municipios con más de 5.000 usuarios: vehículos con **equipo de compactación** (excepto aprovechables, RCD y no compactables); cajas de compactación cerrada hacia disposición final.
  - Nunca se compacta frente a centros educativos, hospitales, clínicas o centros asistenciales.
- Decreto 1381 de 2024 (sustituye lo que el Decreto 596 de 2016 había agregado al Decreto 1077): el aprovechamiento es exclusivo de las organizaciones de recicladores de oficio por 15 años e incluye el transporte selectivo hasta una ECA. En Rutea, las rutas de aprovechables son selectivas y terminan en una ECA, no en el relleno.
- Resolución 2184 de 2019: código de colores. **Blanco** aprovechables, **verde** orgánicos aprovechables, **negro** no aprovechables. Rutea usa este código como semántica visual.
- Bogotá: PGIRS adoptado por Decreto 345 de 2020, hoy en el Título 12 del Decreto 653 de 2025, modificado por el Decreto 045 de 2026. Contratos de aseo prorrogados hasta 2028 (Resolución CRA 1027 de 2026, según prensa: verificar).

Si modificas o agregas una regla: actualiza `docs/normativa.md`, el `NormRef` en código y su test en el mismo cambio.

## Frontend

- React + TypeScript + Vite. Mapa con MapLibre GL; capas de datos con deck.gl (`PathLayer`, `TripsLayer`, `ScatterplotLayer`).
- Diseño oscuro primero, con tokens en `web/src/styles/tokens.css`. No se escriben colores literales en componentes.
- Los colores de las corrientes de residuo siguen la Resolución 2184 de 2019 (adaptados para verse sobre un mapa oscuro; ver `docs/diseno.md`).
- Cifras con `font-variant-numeric: tabular-nums`. Respeta `prefers-reduced-motion` (sin animación de camiones por defecto en ese caso).
- El plan de demo (`pnpm demo:data`, requiere `data/osm/suba.rgraph` y `data/osm/suba-sitios-sensibles.csv`) va sobre las calles reales de OSM. Los contenedores y sus cargas son simulados, y la UI lo dice explícitamente; nunca se presentan como datos de operación.

## Flujo de trabajo

- Ramas: `feat/…`, `fix/…`, `docs/…`, `perf/…`. Commits en inglés, estilo Conventional Commits.
- Un cambio de rendimiento del solver incluye en la descripción del PR la tabla del arnés antes y después.
- Decisiones de arquitectura: un ADR nuevo en `docs/adr/` (plantilla en `docs/adr/0000-plantilla.md`).
- Si no sabes algo del dominio o de la norma, pregunta o marca `PENDIENTE_VERIFICAR`. No inventes artículos.

## Fase actual

**F5 — Operación en tiempo real.** Ver `docs/roadmap.md` para el estado y el criterio de cierre.
