# Instancias de Suba

Instancias CVRP de la operación real de Suba (ASE 5) en formato CVRPLIB, con la matriz completa (`EDGE_WEIGHT_TYPE : EXPLICIT`, `FULL_MATRIX`). El arnés de benchmarks las lee igual que las del set X.

- **Depósito (nodo 1):** cada ruta es el turno de un compactador. Sale de la base de Fontibón, recoge hasta llenarse y termina en la portería noreste del Relleno Sanitario Doña Juana. La matriz asimétrica lo permite: la fila del nodo 1 son distancias desde la base y su columna, distancias hasta la portería. El regreso de la portería a la base es igual para todos los camiones y no entra; el `COMMENT` dice cuánto mide.
- Antes de este modelo, el depósito era la portería en los dos sentidos (`Suba-n807-s1`). El benchmark de `docs/benchmarks/f3-suba-60s` es de esa versión.
- **Clientes:** dos instancias, con su carga en kg:
  - `SubaBase-n807-s1`: los 806 contenedores negros simulados de `SimulatedContainers`, con la base de 2022 y la carga media de las toneladas. Está congelada (ADR 0013): es la instancia de las cifras de F3 a F6, y sus bytes no cambian.
  - `SubaNoche-n321-s1-f100`: los 320 contenedores negros del Plan de Contenerización de 2026. Su ubicación no se publica, así que se simula con el mismo método y la misma semilla. Cada uno tiene un tamaño al azar entre 3.200, 2.400 y 1.100 L, y carga por capacidad (150 kg/m³ × llenado del 100 %).
- **Distancias:** metros por la red vial de `data/osm/suba.rgraph`. Son asimétricas por los sentidos viales.
- **Capacidad:** 8.000 kg, el compactador de 32 yardas, el más común del ASE 5.
- **Sin BKS:** nadie ha publicado una solución para estas instancias, así que el arnés deja vacíos la BKS y el gap.

Las cifras y sus fuentes están en [`docs/datos.md`](../../docs/datos.md). El `COMMENT` de cada `.vrp` lleva la semilla y el SHA-256 del extracto OSM del que sale.

## Generar

Los `.vrp` no se suben al repo (pesan unos 3,4 MB y 0,5 MB). Se generan desde el grafo:

```bash
mvn -pl rutea-geo compile exec:java                 # data/osm/suba.rgraph (requiere el extracto; ver data/osm/README.md)
mvn -pl rutea-app -am install -DskipTests
mvn -pl rutea-app exec:java@suba-instance           # data/suba/SubaBase-n807-s1.vrp y SubaNoche-n321-s1-f100.vrp
```

Con `-Dexec.args="<grafo> <dir> --seed <n>"` se genera otra ubicación de contenedores.

## Licencia

Las distancias derivan de OpenStreetMap: © colaboradores de OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Toda instancia o resultado derivado que se publique lleva esa atribución.
