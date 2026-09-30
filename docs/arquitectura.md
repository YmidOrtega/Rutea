# Arquitectura

Rutea es un **monolito modular**: módulos Maven con fronteras claras y dependencias en una sola dirección, desplegados como un solo proceso. Ver [ADR 0001](adr/0001-monolito-modular.md).

## Vista general

```mermaid
flowchart LR
  subgraph datos[Datos de entrada]
    OSM[Extracto OSM]
    CVRPLIB[Instancias CVRPLIB]
  end
  subgraph backend[Backend Java 21]
    GEO[rutea-geo<br/>grafo vial y matriz]
    DOM[rutea-domain<br/>residuos y normativa]
    SIM[rutea-sim<br/>reloj, sensores, eventos]
    SOL[rutea-solver<br/>CVRP · ALNS]
    APP[rutea-app<br/>planning · API · WebSocket]
  end
  DB[(PostgreSQL + PostGIS)]
  WEB[web<br/>React · MapLibre · deck.gl]

  OSM --> GEO
  CVRPLIB --> SOL
  GEO --> APP
  DOM --> APP
  SIM --> APP
  DOM --> SIM
  APP -->|instancia CVRP| SOL
  SOL -->|rutas| APP
  APP --> DB
  APP -->|REST + WebSocket| WEB
```

## Módulos

### rutea-solver

Java puro, sin dependencias en tiempo de ejecución.

- `model`: `Instance` (capacidad, demandas, matriz), `DistanceMatrix` (enteros, arreglo plano), `Solution` (rutas como `int[]`).
- `io`: lectura de `.vrp` y `.sol` de CVRPLIB.
- `eval`: `Evaluator`, la única fuente de verdad del costo y la factibilidad.
- `api`: interfaz `Solver` y `SolveOptions` (semilla, límite de tiempo).
- `baseline`: solver de referencia por vecino más cercano.
- `bench`: arnés que corre un solver sobre un directorio de instancias y escribe CSV.
- Más adelante: `construct` (Clarke-Wright), `local` (búsqueda local), `alns`.

### rutea-domain

- `WasteStream`: corrientes de residuo con su color oficial.
- `NormRef`: referencia normativa (norma, artículo, URL, estado de verificación).
- `CollectionRules`: reglas del Decreto 1077 de 2015 que el planificador debe respetar.
- Más adelante: `Container`, `Vehicle`, `Facility` (base, ECA, relleno), `Shift`.

### rutea-geo (F3)

Grafo vial en arreglos compactos (CSR), Dijkstra y A*, y construcción de la matriz de distancias y tiempos.

### rutea-sim (F5)

Reloj acelerable, sensores de llenado y generador de eventos. Publica en un bus interno que consume `rutea-app`.

### rutea-app

Spring Boot. Paquetes:

- `planning`: traduce el problema de residuos a instancias CVRP y las rutas de vuelta a geometrías.
- `api`: REST (`/api/...`) y WebSocket.
- `persistence`: PostgreSQL + PostGIS (desde F6).

## Flujo de una replanificación (F5)

```mermaid
sequenceDiagram
  participant SIM as rutea-sim
  participant APP as rutea-app
  participant SOL as rutea-solver
  participant WEB as web
  SIM->>APP: Evento (avería del camión 3)
  APP->>APP: Congela lo recorrido y arma una sub-instancia
  APP->>SOL: solve(instancia, plan vigente, límite 2 s)
  SOL-->>APP: Nuevo plan
  APP-->>WEB: plan.updated (WebSocket)
  WEB->>WEB: Anima el cambio de rutas
```
