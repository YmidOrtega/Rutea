# Contenedores de los operadores

Los contenedores negros que cada operador publica "en campo" en su capa pública de ArcGIS ([ADR 0013](../../docs/adr/0013-contenedores-reales-de-2026.md)). Solo las ASE 1, 2 y 4 publican la ubicación. Las ASE 3 y 5 publican solo la cantidad del Plan de Contenerización de 2026 (91 y 320), así que sus contenedores se simulan. Las fuentes de cada cifra están en [`docs/datos.md`](../../docs/datos.md), en "Inventario de fuentes por ASE".

Los archivos no se suben al repo, porque las capas no declaran licencia (`PENDIENTE_VERIFICAR`). Se descargan con:

```bash
mvn -pl rutea-app exec:java@operator-containers
```

El comando escribe un CSV por ASE (`code,lat_e7,lon_e7,liters,locality,route,shift`, ordenado por código) y `fuentes.csv`, con la URL, el filtro, la fecha de última edición de la capa, la fecha de descarga y el SHA-256 de cada archivo. También comprueba que cada contenedor caiga dentro de su ASE. La localidad, la microrruta y el turno van como los escribe el operador; la localidad del catálogo se asigna por polígono. La capa del ASE 4 no trae el turno.

## Extracto en uso

| ASE | Archivo | Contenedores | Capacidad | Turno | Capa editada | SHA-256 |
|---|---|---:|---|---|---|---|
| 1 · Arzobispo | `ase1-contenedores-negros.csv` | 168 | 153 de 1.100 L, 15 de 2.200 L | 155 de día, 13 de noche | 2026-10-27 | `8b50bbffbeb665d942f54043c66b36e99836fedaa7d7802885b3551de716eb52` |
| 2 · Tunjuelo | `ase2-contenedores-negros.csv` | 990 | 2.200 L | 609 de día, 381 de noche | 2026-10-07 | `84883fe6c585ca97b0ade7519d828f8906758327d37e938d750058f02cf6b32b` |
| 4 · Salitre | `ase4-contenedores-negros.csv` | 763 | 2.400 L | – | 2026-10-02 | `d0dd5508e40ed2d54d85ac06cd3e09150c5a28108f807638c946055e1d99527d` |

Descargado el 2026-10-29. Dos descargas seguidas dieron los mismos bytes, y ningún contenedor cayó fuera de su ASE.

**Las capas cambian.** Los operadores las editan cada mes, y Tunjuelo publica un servicio nuevo con la fecha en el nombre (`CONTENEDORES_OCTUBRE_2026`). Si una descarga da otro SHA-256, cambió la capa: se actualiza esta tabla y se anota en el commit, porque las instancias y los planes cambian con ella. Si Tunjuelo publica un servicio nuevo, se cambia la URL en `OperatorContainers.LAYERS`.
