# ADR 0012: Seleccionar localidades, planificar por ASE

- Estado: aceptada
- Fecha: 2026-10-28

## Contexto

Hasta F6, Rutea planifica una sola zona: Suba, que es a la vez una localidad y un Área de Servicio Exclusivo (ASE 5, operada por Torca). Todo lo que la define está fijo en el código: `AreaSpec.SUBA`, `SubaFigures`, `SubaScenario`, `suba-upz.geojson` y los sitios sensibles de Suba.

El siguiente paso es poder escoger cualquier conjunto de localidades de Bogotá (una, cinco, las veinte o las que sean) y que el plan funcione. Hay cuatro fuerzas en juego:

- **La operación real no se organiza por localidad.** Bogotá está dividida en 5 ASE. Cada una tiene un operador con su base y su flota, y un camión no recoge fuera de su ASE. Una ASE puede abarcar varias localidades. `PENDIENTE_VERIFICAR`: qué localidades forman cada ASE y quién la opera, contra la fuente de la UAESP.
- **El solver tiene un rango medido.** Con 807 nodos no hizo falta ajustar nada (F3). El ajuste previsto (memoria n² de `Plan` y ahorros de Clarke-Wright entre todos los pares) aparece cerca de 1.700 nodos. Un CVRP de toda Bogotá tendría unos 5.400 contenedores negros (la mitad de 10.746).
- **Las combinaciones no se pueden precalcular.** Con 20 localidades hay más de un millón de subconjuntos, así que no puede haber un grafo ni una instancia por selección.
- **Lo ya publicado no se puede mover sin medirlo.** Las cifras de F3 a F6 salen del grafo de Suba (su SHA-256 va en el `COMMENT` de cada `.vrp`).

## Decisión

1. **Dos unidades con papeles distintos.** La **localidad** es la unidad de selección: es lo que el usuario escoge. La **ASE** es la unidad de planificación: define quién recoge. Una selección se reparte por ASE, y cada ASE que tenga al menos una localidad elegida se convierte en una instancia CVRP independiente, con su base, su flota y sus cifras. Todas descargan en Doña Juana (las rutas de aprovechables terminan en una ECA, como hoy). Las instancias se resuelven en paralelo, con un hilo por ASE y sin estado compartido.
2. **Catálogo versionado.** Un archivo del repo asigna a cada localidad su relación de OSM y su ASE, y a cada ASE su operador, su base (nodo de OSM) y sus cifras con fuente. Lo que no esté verificado queda marcado `PENDIENTE_VERIFICAR`. Sumapaz entra al catálogo marcada como rural, y su modelo de recolección también queda `PENDIENTE_VERIFICAR`.
3. **Contenedores estables ante la selección.** Se generan una sola vez por ASE completa, con semilla, y la selección solo filtra. Un contenedor queda en el mismo lugar y con la misma carga se elija Suba sola o Bogotá entera. Elegir todas las localidades de una ASE reproduce exactamente la instancia de esa ASE.
4. **Un solo grafo para Bogotá.** Incluye todas las calles públicas de las 20 localidades y los sitios (las bases de los operadores, la portería de Doña Juana y las ECA). Se construye una vez y se publica en un release, como el de Suba (ADR 0010). Las matrices se arman por instancia, solo con los nodos de la selección.
5. **La flota es un resultado.** En una selección parcial, la flota no se prorratea: `ShiftScheduler` da el mínimo de camiones. La flota que declara el operador funciona como tope, y el plan avisa si se pasa de ella.
6. **La selección viaja en la petición.** La API recibe las localidades y responde un plan por ASE. El mapa muestra cada ASE con su flota. Los eventos en vivo llevan su ASE.
7. **No se modela una flota que cruce ASE.** Esa pregunta ("¿qué pasaría si una sola flota atendiera estas localidades?") es un contrafactual útil, pero no es la operación real. Si se quiere estudiar, va en un ADR aparte.

## Consecuencias

- Cualquier selección funciona sin precalcular combinaciones, y nunca se resuelve un CVRP de toda la ciudad. La instancia más grande posible es la ASE más grande. Hay que medir su tamaño: si pasa de unos 1.700 nodos, se aplica el ajuste previsto en F3.
- Elegir 20 localidades cuesta lo mismo que resolver las 5 ASE a la vez, una por hilo.
- Suba sobre el grafo de Bogotá no da los mismos km que sobre su grafo propio, porque una ruta puede tomar calles locales de las localidades vecinas. Las cifras de F3 a F6 quedan atadas a su grafo, y la diferencia se mide y se publica; no se reescriben.
- El grafo y el release de datos crecen. El tamaño, el tiempo de construcción y el de la matriz se miden antes de publicarlos.
- Las cifras por ASE dependen de fuentes que todavía no están reunidas (informes de la Superservicios por operador y participación de cada ASE en el PGIRS). Mientras falten, el plan funciona, pero las cifras sin fuente van marcadas.
- Queda abierto qué zonas recogen casa por casa en lugar de con contenedores ("Más adelante" en el roadmap). Si alguna ASE lo hace, su instancia cambia de modelo (CARP llevado a CVRP), no solo de datos.
