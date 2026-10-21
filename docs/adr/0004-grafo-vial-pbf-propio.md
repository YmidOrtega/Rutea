# ADR 0004: Grafo vial desde un lector PBF propio

- Estado: aceptada
- Fecha: 2026-10-20

## Contexto

F3 necesita la red vial real de Suba y el corredor hasta el relleno Doña Juana para construir una matriz de unos 1.700 contenedores. Los datos vienen de OpenStreetMap. Había tres caminos: un lector PBF propio en Java; una consulta Overpass guardada como XML y leída con StAX; o recortar con `osmium-tool` fuera de Java.

Fuerzas en juego:

- `rutea-geo` no depende de ningún módulo, y el proyecto prefiere no tener dependencias externas donde el detalle es el punto (ADR 0002).
- Reproducibilidad: el mismo extracto debe dar el mismo grafo y la misma matriz, igual que una semilla da la misma corrida.
- Quien clone el repo no debería tener que instalar herramientas aparte de Java y Maven.
- El XML de Overpass es varias veces más pesado que el PBF y sus datos cambian con cada consulta.

## Decisión

`rutea-geo` lee directamente archivos `.osm.pbf` con un decodificador propio: el formato de mensajes de protobuf decodificado a mano y zlib con `java.util.zip.Inflater`, sin otras librerías. La entrada es un snapshot fechado del extracto de Colombia de Geofabrik. Su URL, fecha y SHA-256 quedan en `data/osm/README.md`. El archivo no se sube al repo.

El grafo compilado se guarda en un formato binario propio, versionado, que carga en milisegundos y se invalida si cambia el SHA-256 del extracto.

## Diseño

1. **Lectura en dos pasadas.** La primera lee los `Way` con etiqueta `highway` y la relación del límite de Suba, y marca los nodos que se usan. La segunda solo guarda las coordenadas de esos nodos (`DenseNodes`). Los bloques del PBF son independientes: se decodifican en paralelo, sin estado compartido mutable.
2. **Recorte.** Se toman todas las vías transitables por un camión dentro del polígono de Suba y las vías principales (`motorway`, `trunk`, `primary` y sus `_link`) dentro del rectángulo de Bogotá. Después se conserva solo la mayor componente fuertemente conexa, para que todo contenedor sea alcanzable desde el depósito y el relleno, y pueda volver.
3. **Sentidos.** `oneway=yes|-1` y `junction=roundabout` generan arcos en un solo sentido. El resto genera dos arcos. Así la matriz es asimétrica de verdad, algo que el solver ya soporta desde F1.
4. **Compactación.** Los nodos del grafo son solo intersecciones y extremos de vía. Los nodos intermedios se pasan a la geometría del arco, que se guarda aparte para que el frontend dibuje las calles reales.
5. **Pesos enteros.** Cada arco guarda longitud en metros y tiempo en segundos, ambos `int` con redondeo `nint`. La longitud se calcula con la fórmula de haversine sobre la geometría. El tiempo usa `maxspeed` si existe y, si no, una velocidad por tipo de vía. Esa tabla de velocidades es una suposición documentada, no un dato medido.
6. **Formato CSR.** Arreglos `offsets`, `targets`, `meters` y `seconds`, más las coordenadas de los nodos en enteros de 1e-7 grados, igual que OSM. El archivo lleva un número mágico, una versión y el SHA-256 de origen.

## Consecuencias

- Cero dependencias nuevas y una ingesta reproducible byte a byte desde un snapshot fijo.
- Más trabajo que Overpass: hay que escribir y probar el decodificador. Los tests necesitan un PBF diminuto como fixture, que se genera desde los propios tests con un codificador mínimo para no depender de `osmium`.
- Descargar el extracto de Colombia es un paso manual documentado. Si pesa demasiado para el flujo de clonar y ejecutar de F6, se publica el grafo compilado como artefacto de versión.
- Pendiente: la tabla de velocidades por tipo de vía, si `service` y `living_street` entran al grafo, y las restricciones de acceso para camiones (`hgv`, `maxweight`). Se deciden con el grafo construido, mirando qué contenedores quedan aislados.
