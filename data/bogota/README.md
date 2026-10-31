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

## Localidades con puerta a puerta (F8)

La recolección de no aprovechables de una localidad: sus contenedores y el puerta a puerta por sus calles locales en trozos de hasta 500 m (ADR 0014), una instancia por franja. Una franja es un grupo de días, lunes, miércoles y viernes (`LMV`) o martes, jueves y sábado (`MJS`), en un turno, de día desde las 06:00 (`Dia`) o de noche desde las 18:00 (`Noche`). Las UPZ se reparten entre las cuatro, parejas en metros de calle, y cada contenedor va en su turno de cargue lateral.

| Instancia | Franja | Contenedores | Trozos |
|---|---|---:|---:|
| `SubaLMVDia-n977-s7-f100` | lunes, miércoles y viernes, de día | 0 | 976 |
| `SubaLMVNoche-n1032-s7-f100` | lunes, miércoles y viernes, de noche | 320 simulados | 711 |
| `SubaMJSDia-n841-s7-f100` | martes, jueves y sábado, de día | 0 | 840 |
| `SubaMJSNoche-n1068-s7-f100` | martes, jueves y sábado, de noche | 320 simulados | 747 |

- **Nodos:** primero los contenedores, que se visitan en un vértice, y después los trozos. Llegar a un trozo cuesta el camino hasta su entrada más lo que mide recorrerlo recogiendo; de él se sale por su último vértice.
- **Demanda de un trozo:** sus metros × los kg por metro de la localidad, que el `COMMENT` trae con su cuenta. Las 3 pasadas por semana, los turnos fuera del ASE 1 y los 8.000 kg del compactador de carga trasera son `PENDIENTE_VERIFICAR`.
- **Otras localidades:** la misma clase con sus números, desde la raíz del repo: `mvn -pl rutea-app exec:java -Dexec.mainClass=me.ymid.rutea.app.scenario.ExportLocalityInstances -Dexec.args="data/osm/bogota.rgraph data/ase data/bogota 2 8"` (Chapinero y Kennedy).

## Generar

Los `.vrp` no se suben al repo (pesan unos 6,1 MB entre los siete de las ASE y 20 MB entre los cuatro de Suba):

```bash
mvn -pl rutea-geo compile exec:java@bogota            # data/osm/bogota.rgraph
mvn -pl rutea-app exec:java@operator-containers        # data/ase/*.csv
mvn -pl rutea-app -am install -DskipTests
mvn -pl rutea-app exec:java@bogota-instances           # data/bogota/ASE*.vrp y SubaNoche-*.vrp
mvn -pl rutea-app exec:java@locality-instances         # data/bogota/SubaLMV*.vrp y SubaMJS*.vrp
```

## Datos de entrada

El grafo, los sitios sensibles y los contenedores de los operadores de los que salen estas instancias están en el release [`data-bogota-20261029`](https://github.com/YmidOrtega/Rutea/releases/tag/data-bogota-20261029). [`SHA256SUMS`](SHA256SUMS), en este directorio, es la referencia: `docker compose up` los descarga del release y los verifica contra ella, no contra las sumas que vienen con la descarga. Si un archivo cambia (otro extracto de OSM, otra versión de una capa), se publica un release nuevo y se actualizan `SHA256SUMS` y `compose.yaml` en el mismo cambio.

## Licencia

Las distancias derivan de OpenStreetMap: © colaboradores de OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Toda instancia o resultado derivado que se publique lleva esa atribución.
