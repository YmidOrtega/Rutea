# Instancias de Bogotá por ASE

Las instancias de cargue lateral de las cinco ASE sobre el grafo de todo Bogotá, una por turno, en formato CVRPLIB con la matriz completa (`EDGE_WEIGHT_TYPE : EXPLICIT`, `FULL_MATRIX`). Son las mismas que arma `CityPlanner` cuando se eligen las 20 localidades: ALNS con semilla 7 y 2.000 iteraciones da sobre ellas exactamente los costos y viajes del plan de la ciudad. El arnés las lee igual que las del set X.

| Instancia | ASE | Turno | Contenedores |
|---|---:|---|---|
| `ASE1Dia-n156-f100` | 1 | 06:00, 10 h | 155 del operador |
| `ASE1Noche-n14-f100` | 1 | 18:00, 10 h | 13 del operador |
| `ASE2Dia-n610-f100` | 2 | 06:00, 10 h | 609 del operador |
| `ASE2Noche-n382-f100` | 2 | 18:30, 10 h | 381 del operador |
| `ASE3Dia-n92-s7-f100` | 3 | 06:00, 8 h | 91 simulados de 1.100 L |
| `ASE4Dia-n764-f100` | 4 | 06:00, 9 h | 763 del operador |
| `SubaNoche-n321-s7-f100` | 5 | 18:00, 8 h | 320 simulados |

- **Depósito (nodo 1):** cada ruta sale de la base del operador y termina en la portería de Doña Juana; la matriz asimétrica lo permite, como en `data/suba`. El regreso a la base no entra, y el `COMMENT` dice cuánto mide.
- **Clientes:** los contenedores negros de cargue lateral del turno, con carga por capacidad (150 kg/m³ × llenado del 100 %, el `f100` del nombre).
  - ASE 1, 2 y 4: los contenedores "en campo" de la capa pública de cada operador (`data/ase`). El `COMMENT` lleva el archivo y su SHA-256.
  - ASE 3 y 5: la cantidad oficial del Plan de Contenerización de 2026, con ubicación simulada (`s7`, la semilla del plan de la ciudad). `SubaNoche-n321-s7-f100` es la misma Suba de `data/suba/SubaNoche-n321-s1-f100` con otra semilla.
- **Distancias:** metros por la red vial de `data/osm/bogota.rgraph`.
- **Capacidad:** 8.000 kg en todas (`PENDIENTE_VERIFICAR` fuera de Suba; ver `docs/datos.md`).
- **Lo que no entra:** el formato CVRPLIB solo tiene capacidad. El turno, los viajes por camión y la descarga los agrega `CityPlanner` al repartir los viajes en camiones; el arnés mide solo los km.
- **Sin BKS:** el arnés deja vacíos la BKS y el gap.

## Generar

Los `.vrp` no se suben al repo (pesan unos 6,1 MB entre los siete):

```bash
mvn -pl rutea-geo compile exec:java@bogota            # data/osm/bogota.rgraph
mvn -pl rutea-app exec:java@operator-containers        # data/ase/*.csv
mvn -pl rutea-app -am install -DskipTests
mvn -pl rutea-app exec:java@bogota-instances           # data/bogota/*.vrp
```

## Licencia

Las distancias derivan de OpenStreetMap: © colaboradores de OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Toda instancia o resultado derivado que se publique lleva esa atribución.
