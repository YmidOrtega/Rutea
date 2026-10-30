# ADR 0014: Puerta a puerta como CVRP sobre tramos de calle agrupados

- Estado: propuesta
- Fecha: 2026-10-29

## Contexto

Con la prórroga de los contratos de 2026, los operadores retiraron contenedores y pasaron la recolección de no aprovechables a puerta a puerta (ADR 0013, `docs/datos.md`). Lo que queda en contenedores es poco: en el modo Ciudad, Santa Fe sale con 0 contenedores, Chapinero con 2, Teusaquillo con 30 y Usaquén con 57. La operación en vivo de Suba se ve llena solo porque usa la base de 2022, que ya no describe la ciudad. Para que el modo en vivo sirva en cualquier localidad (F8), hay que modelar el puerta a puerta.

En el puerta a puerta el camión recorre la calle y recoge a lado y lado. Es un problema de rutas por arcos (CARP). El solver es un CVRP y no se va a reescribir: el roadmap ya preveía convertir cada tramo con demanda en un cliente.

**Medido en Suba** (`suba.rgraph`, extracto `colombia-261019`), tramos de calle local (terciaria, sin clasificar, residencial y calle compartida), contando una vez los de doble sentido:

| Clase | Tramos | km |
|---|---:|---:|
| Terciaria | 1.385 | 110,8 |
| Sin clasificar | 26 | 12,1 |
| Residencial | 7.710 | 571,0 |
| **Calle local** | **9.121** | **693,9** |

Un tramo mide 76 m en promedio: más o menos una cuadra. Con un cliente por tramo, la matriz de Suba tendría 9.122² celdas, unos 330 MB en `int`, y el solver trabajaría con 11 veces más clientes que los 807 de `SubaBase-n807-s1`.

**Demanda, con fuente:**
- La UAESP dispone en promedio **5.614 t/día** de no aprovechables en Doña Juana ([El Espectador, 18 sep. 2025](https://www.elespectador.com/bogota/a-diario-se-recogen-mas-de-5600-toneladas-de-residuos-no-aprovechables-en-bogota/)).
- La población urbana proyectada para 2026 es de **8.134.378** habitantes, **1.347.097** de ellos en Suba ([DANE, proyecciones por localidad 2018–2035](https://www.dane.gov.co/index.php/estadisticas-por-tema/demografia-y-poblacion/proyecciones-de-poblacion/proyecciones-de-poblacion-bogota), serie de 2021; hay una actualización de 2025, del Contrato 500 entre el DANE y la SDP, que no se encontró publicada).
- Repartidas por población, a Suba le tocan **929,7 t/día**. De esas, lo que recoge el cargue lateral de sus contenedores ya está en el plan de la ciudad, y el resto es puerta a puerta.

## Decisión

Propuesta, para cerrar con mediciones en el paso 1 de F8:

1. **Un cliente por grupo de tramos, no por tramo.** Los tramos de calle local se agrupan en trozos contiguos, cada uno de longitud acotada. Cada trozo es un cliente del CVRP: el camión entra por un extremo, lo recorre recogiendo y sale por el otro. La matriz va de la salida de un trozo a la entrada del siguiente. El recorrido interno del trozo entra como tiempo de servicio, a velocidad de recolección, y no como distancia de la matriz.
2. **Demanda por longitud de calle.** Las toneladas de la localidad (las de la UAESP repartidas por población, menos lo que recogen sus contenedores) se reparten entre los trozos según su longitud, el mismo indicador que ya usan los contenedores simulados. Es un supuesto, y así se marca: con más datos, por ejemplo edificios o viviendas por manzana, se reemplaza.
3. **Los dos esquemas en la misma instancia.** Los contenedores de la localidad siguen siendo clientes puntuales, y los trozos se suman a ellos. El solver, el `Evaluator` y el arnés no cambian.
4. **El tamaño del trozo se decide midiendo**, no aquí. Se prueban varias longitudes máximas en Suba, y se mide cuántos clientes salen, cuánto tarda la matriz, cuánto tarda ALNS y cuánto se aleja el costo del de los tramos sin agrupar, en una zona chica donde eso último todavía se pueda resolver.

## Consecuencias

- Se reutilizan el solver, el `Evaluator`, el arnés y la operación en vivo, porque un trozo es un cliente más.
- Agrupar pierde optimalidad: el orden dentro de un trozo queda fijo. El paso 1 mide cuánto.
- La demanda por longitud no distingue estratos ni densidad. La UAESP midió producción por estrato y localidad en 2017, pero la tabla no está publicada (`docs/datos.md`, "Patrón por zona").
- Quedan `PENDIENTE_VERIFICAR`: la frecuencia del puerta a puerta (cuántas veces por semana pasa por cada calle), la velocidad de recolección y la capacidad de los compactadores de carga trasera. Ninguna está publicada por los operadores en lo que se ha encontrado.
- Las avenidas quedan fuera de la primera versión: el puerta a puerta sobre arterias tiene otra logística (cruces, carriles exclusivos) y su demanda no se puede separar con lo que hay.
