# Instancias de Suba

Instancias CVRP de la operación real de Suba (ASE 5) en formato CVRPLIB, con la matriz completa (`EDGE_WEIGHT_TYPE : EXPLICIT`, `FULL_MATRIX`). El arnés de benchmarks las lee igual que las del set X.

- **Depósito (nodo 1):** la portería noreste del Relleno Sanitario Doña Juana. Cada ruta es un viaje que sale del relleno, recoge hasta llenar el compactador y vuelve a descargar. El tramo desde y hacia la base de Fontibón no entra.
- **Clientes:** los contenedores negros simulados de `SimulatedContainers`, con su carga en kg.
- **Distancias:** metros por la red vial de `data/osm/suba.rgraph`. Son asimétricas por los sentidos viales.
- **Capacidad:** 8.000 kg, el compactador de 32 yardas, el más común del ASE 5.
- **Sin BKS:** nadie ha publicado una solución para estas instancias, así que el arnés deja vacíos la BKS y el gap.

Las cifras y sus fuentes están en [`docs/datos.md`](../../docs/datos.md). El `COMMENT` de cada `.vrp` lleva la semilla y el SHA-256 del extracto OSM del que sale.

## Generar

Los `.vrp` no se suben al repo (pesan unos 3,4 MB). Se generan desde el grafo:

```bash
mvn -pl rutea-geo compile exec:java                 # data/osm/suba.rgraph (requiere el extracto; ver data/osm/README.md)
mvn -pl rutea-app -am install -DskipTests
mvn -pl rutea-app exec:java@suba-instance           # data/suba/Suba-n807-s1.vrp
```

Con `-Dexec.args="<grafo> <dir> --seed <n>"` se genera otra ubicación de contenedores.

## Licencia

Las distancias derivan de OpenStreetMap: © colaboradores de OpenStreetMap, [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Toda instancia o resultado derivado que se publique lleva esa atribución.
