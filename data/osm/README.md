# Extracto de OpenStreetMap

`rutea-geo` construye el grafo vial desde un snapshot fijo del extracto de Colombia que publica Geofabrik ([ADR 0004](../../docs/adr/0004-grafo-vial-pbf-propio.md)). El archivo no se sube al repo (pesa 333 MB); se descarga una vez:

```bash
cd data/osm
curl -fLO https://download.geofabrik.de/south-america/colombia-261019.osm.pbf
curl -fLO https://download.geofabrik.de/south-america/colombia-261019.osm.pbf.md5
md5sum -c colombia-261019.osm.pbf.md5
sha256sum colombia-261019.osm.pbf
```

| Campo | Valor |
|---|---|
| Archivo | `colombia-261019.osm.pbf` (datos de OSM al 2026-10-19) |
| Tamaño | 332.536.201 bytes |
| SHA-256 | `13727d617982f27eb4f443467cd6431d9f3653e2d84f439497867cf67f68c3f1` |
| Contenido | 48.316.739 nodos, 5.294.162 vías (1.157.004 con `highway`), 38.465 relaciones |
| Límite de Suba | relación [2214884](https://www.openstreetmap.org/relation/2214884), "Localidad Suba", `admin_level=8` |

El grafo de todo Bogotá (F7, ADR 0012) sale con `mvn -pl rutea-geo compile exec:java@bogota` en `bogota.rgraph` (7,5 MB). Tampoco se sube; está en el release de Bogotá (abajo).

Del extracto también salen, con `mvn -pl rutea-app exec:java@sensitive-sites`, los sitios sensibles para R02 y R07 (`suba-sitios-sensibles.csv`, unos 100 KB; tampoco se sube). Los de todo el Distrito, para planificar cualquier selección de localidades, salen con `mvn -pl rutea-app exec:java@bogota-sensitive-sites` en `bogota-sitios-sensibles.csv` (490 KB; requiere `bogota.rgraph`).

Geofabrik guarda los snapshots fechados por un tiempo limitado. Si este ya no está, descarga el más reciente, actualiza esta tabla y anota en el commit que cambió el extracto: el grafo, la matriz y las rutas cambian con él.

## Sin descargar el extracto

Para correr la app no hace falta el extracto: `docker compose up` descarga el grafo y los sitios ya construidos del release [`data-osm-suba-20261019`](https://github.com/YmidOrtega/Rutea/releases/tag/data-osm-suba-20261019) y los verifica contra [`SHA256SUMS`](SHA256SUMS). Reconstruirlos desde el extracto da los mismos bytes. Si cambia el extracto, se publica un release nuevo y se actualizan `SHA256SUMS` y `compose.yaml` en el mismo cambio.

Lo de Bogotá (F7) va en un release aparte, [`data-bogota-20261029`](https://github.com/YmidOrtega/Rutea/releases/tag/data-bogota-20261029): `bogota.rgraph` y `bogota-sitios-sensibles.csv`, que también se reconstruyen con los mismos bytes (comprobado el 2026-10-29), y los contenedores de los operadores. Se verifican contra [`data/bogota/SHA256SUMS`](../bogota/SHA256SUMS).

## Licencia

© colaboradores de OpenStreetMap. Datos disponibles bajo la [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Todo mapa o resultado derivado que se publique debe llevar la atribución "© OpenStreetMap contributors".
