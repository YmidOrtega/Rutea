# Rutea

Planificador de microrrutas de recolección de residuos sólidos para Bogotá (piloto: Suba), construido sobre un solver CVRP propio en Java 21 y medido contra las instancias públicas de CVRPLIB.

## Requisitos

- JDK 21
- Maven 3.9+ (o el wrapper incluido `./mvnw`)
- Node.js 22+ y pnpm 11 (solo para `web/`; la versión exacta está en `packageManager`)

## Comandos

```bash
./mvnw verify                  # compila y corre todos los tests
./mvnw -pl rutea-solver test   # solo el solver
./mvnw -pl rutea-app spring-boot:run   # API en http://localhost:8080

cd web && pnpm install
pnpm dev                       # http://localhost:5173 (proxy /api → :8080)
pnpm build
```

## Documentación

- [Guía para agentes y colaboradores](AGENTS.md)
- [Arquitectura](docs/arquitectura.md)
- [Roadmap](docs/roadmap.md)
- [Normativa](docs/normativa.md)
- [Diseño](docs/diseno.md)
- [ADRs](docs/adr/)
- [Instancias CVRPLIB](data/cvrplib/README.md)
