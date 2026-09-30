# Rutea

Planificador de microrrutas de recolección de residuos sólidos para Bogotá (piloto: Suba), construido sobre un solver CVRP propio en Java 21 y medido contra las instancias públicas de CVRPLIB.

## Requisitos

- JDK 21
- Maven 3.9+ (o el wrapper incluido `./mvnw`)

## Comandos

```bash
./mvnw verify                  # compila y corre todos los tests
./mvnw -pl rutea-solver test   # solo el solver
```

## Documentación

- [Guía para agentes y colaboradores](AGENTS.md)
- [Arquitectura](docs/arquitectura.md)
- [Roadmap](docs/roadmap.md)
- [Normativa](docs/normativa.md)
- [Diseño](docs/diseno.md)
- [ADRs](docs/adr/)
- [Instancias CVRPLIB](data/cvrplib/README.md)
