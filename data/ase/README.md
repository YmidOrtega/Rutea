# Contenedores de los operadores

Los contenedores negros que cada operador publica "en campo" en su capa pública de ArcGIS ([ADR 0013](../../docs/adr/0013-contenedores-reales-de-2026.md)). Solo las ASE 1, 2 y 4 publican la ubicación. Las ASE 3 y 5 publican solo la cantidad del Plan de Contenerización de 2026 (91 y 320), así que sus contenedores se simulan. Las fuentes de cada cifra están en [`docs/datos.md`](../../docs/datos.md), en "Inventario de fuentes por ASE".

Los archivos no se suben al repo, porque las capas no declaran licencia (`PENDIENTE_VERIFICAR`). Se descargan con:

```bash
mvn -pl rutea-app exec:java@operator-containers
```

El comando escribe un CSV por ASE (`code,lat_e7,lon_e7,liters,locality,route`, ordenado por código) y `fuentes.csv`, con la URL, el filtro, la fecha de última edición de la capa, la fecha de descarga y el SHA-256 de cada archivo. También comprueba que cada contenedor caiga dentro de su ASE. La localidad y la microrruta van como las escribe el operador; la localidad del catálogo se asigna por polígono.

## Extracto en uso

| ASE | Archivo | Contenedores | Capacidad | Capa editada | SHA-256 |
|---|---|---:|---|---|---|
| 1 · Promoambiental | `ase1-contenedores-negros.csv` | 168 | 153 de 1.100 L, 15 de 2.200 L | 2026-10-27 | `88184de7b335818db036ab09939c1e98d6660fed280e3d6e776c879cdcf82f32` |
| 2 · LIME | `ase2-contenedores-negros.csv` | 990 | 2.200 L | 2026-10-07 | `3cc847bcc961fb018dab2e4ba563cc6355345eb9d16e92416b30e096dabf39c4` |
| 4 · Bogotá Limpia | `ase4-contenedores-negros.csv` | 763 | 2.400 L | 2026-10-02 | `e60479d2205989bce183cfff567547c8c037701eaa3c2a8c5a9da9f3faa9936a` |

Descargado el 2026-10-29. Dos descargas seguidas dieron los mismos bytes, y ningún contenedor cayó fuera de su ASE.

**Las capas cambian.** Los operadores las editan cada mes, y LIME publica un servicio nuevo con la fecha en el nombre (`CONTENEDORES_OCTUBRE_2026`). Si una descarga da otro SHA-256, cambió la capa: se actualiza esta tabla y se anota en el commit, porque las instancias y los planes cambian con ella. Si LIME publica un servicio nuevo, se cambia la URL en `OperatorContainers.LAYERS`.
