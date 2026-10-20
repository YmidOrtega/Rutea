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

Geofabrik guarda los snapshots fechados por un tiempo limitado. Si este ya no está, descarga el más reciente, actualiza esta tabla y anota en el commit que cambió el extracto: el grafo, la matriz y las rutas cambian con él.

## Licencia

© colaboradores de OpenStreetMap. Datos disponibles bajo la [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/). Todo mapa o resultado derivado que se publique debe llevar la atribución "© OpenStreetMap contributors".
