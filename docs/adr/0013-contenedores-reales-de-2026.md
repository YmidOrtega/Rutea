# ADR 0013: Contenedores reales de 2026 y recolección puerta a puerta

- Estado: aceptada
- Fecha: 2026-10-29

## Contexto

Desde F3, los contenedores de Rutea son simulados: 10.746 contenedores de 2022 × la parte del suelo urbano de la ASE × 1/2. Así salen los 806 negros de Suba y los 1.290 a 1.666 de las demás ASE (paso 5 de F7). El inventario de fuentes de `docs/datos.md` muestra que esa base ya no describe la ciudad:

- **La ciudad retiró la mayoría de los contenedores.** Con la prórroga de los contratos en febrero de 2026, la UAESP pidió a cada operador actualizar su Plan de Contenerización, y todos bajaron su capacidad nominal. Lo que sale de la calle pasa a recolección puerta a puerta. Los contenedores blancos quedaron "entregados a terceros", en comodato con organizaciones de recicladores.

  | ASE | Contenedores negros en la calle (2026) | Con ubicación publicada | Plan de 2026 |
  |---|---:|---|---|
  | 1 | 168 | Sí (ArcGIS del operador) | 242 de 1.100 L, con observaciones |
  | 2 | 990 de 2.200 L | Sí | Con observaciones |
  | 3 | 0 de 2.400 L | – | 91 de 1.100 L, aprobado |
  | 4 | 763 de 2.400 L, todos en Engativá | Sí | Sin claridad |
  | 5 | 374 + 70 de adiciones | No | **320**, aprobado |

- **Los contenedores son una parte menor de la recolección.** El ASE 2 lleva 1.804,7 t/día a Doña Juana, y sus 990 contenedores de 2.200 L, llenos a 150 kg/m³ (el supuesto de F5), suman unas 327 t. El resto va en bolsa, frente a cada predio. En el ASE 4, Barrios Unidos ya no tiene contenedores en la calle.
- **Los operadores separan las dos operaciones.** Bogotá Limpia publica macrorrutas de "cargue lateral" (contenedores) y de cargue "manual" (bolsa). LIME declara 5 compactadores de cargue lateral de 32 yd³ dentro de una flota de unos 92. Son rutas y camiones distintos.
- **El puerta a puerta es otro problema.** Recoger bolsas frente a cada predio es recorrer calles, no visitar puntos: un problema de arcos (CARP). Rutea puede llevarlo a CVRP agrupando la demanda por tramo de calle. Pero el ASE 2 tiene miles de tramos de calle local, por encima de los ~1.700 nodos donde F3 previó el primer ajuste del solver.
- **Las cifras de F3 a F6 salen de los 806 contenedores simulados.** Están publicadas en el roadmap y en ymid.me, y su instancia (`SubaBase-n807-s1`) está en el arnés de benchmarks.
- **Las capas de los operadores no declaran licencia.** Son públicas y describen un servicio público, pero redistribuirlas en el repo no está verificado.

## Decisión

1. **Dos operaciones por ASE, dos instancias.** El **cargue lateral** (contenedores) y el **puerta a puerta** (bolsa) son instancias distintas, con su flota, como hoy lo son los negros y los blancos. F7 planifica el cargue lateral. El puerta a puerta queda para un paso propio (punto 6).
2. **Contenedores reales donde el operador publica su ubicación.** En las ASE 1, 2 y 4, los contenedores negros "en campo" salen de las capas de ArcGIS de cada operador, con su capacidad. En las ASE 3 y 5, que publican solo la cantidad, se simula esa cantidad oficial (91 y 320) con el método de F3. La UI y la documentación dicen en cada ASE cuáles contenedores son reales y cuáles simulados.
3. **Los datos de los operadores se descargan, no se versionan.** Un comando baja un extracto fijo de cada capa, con fecha de corte, URL y SHA-256, a `data/ase/`, como el extracto de OSM. Se reproduce desde el mismo extracto, no desde la capa viva, que cambia cada mes. Se versiona solo cuando se verifique la licencia.
4. **La carga sale de la capacidad.** No hay datos de llenado por contenedor. La carga de cada contenedor al recogerlo es su capacidad × 150 kg/m³ × un llenado medio supuesto, igual para todas las ASE (`PENDIENTE_VERIFICAR`). En el ASE 5 se contrasta con el 13 % de toneladas por cargue lateral del PPSA de Área Limpia, y la diferencia se publica.
5. **Las cifras publicadas no se reescriben.** `SubaBase-n807-s1` queda congelada en el arnés como instancia de benchmark. Las cifras de F3 a F6 se rotulan "806 contenedores simulados (supuesto de 2022)". La instancia de 2026 de Suba es nueva (320 contenedores), y la diferencia entre las dos se mide y se publica. El plan de demo y la API pasan a los datos de 2026 cuando la instancia nueva esté medida.
6. **Puerta a puerta: decisión de modelo aplazada.** Para cada ASE se reporta qué parte de sus toneladas cubre el plan de cargue lateral, y el resto se presenta como no planificado, no como resuelto. El modelo (CARP llevado a CVRP por tramo, agrupando por frecuencia y turno) va en su propio ADR, después de medir cuántos nodos daría en el ASE más grande.

## Consecuencias

- Las instancias de cargue lateral se achican: de 806 a 320 contenedores en Suba y de 1.666 a 990 en el ASE 2. Ninguna ASE se acerca a los ~1.700 nodos del ajuste de escala de F3.
- La flota como resultado (paso de F7) gana una prueba real. El ASE 2 declara 5 camiones de cargue lateral para 990 contenedores, y el plan dirá si le alcanzan.
- El ASE 2 queda como la segunda ASE del paso 6: tiene base, flota, toneladas y contenedores ubicados, y su centroide queda a 9,7 km de Doña Juana, frente a 32,6 km en Suba.
- El ASE 3 queda con 91 contenedores simulados: casi toda su recolección es puerta a puerta, que F7 no planifica.
- Un plan de cargue lateral cubre una fracción chica de las toneladas, y la UI tiene que decirlo con la misma claridad con que hoy dice que los contenedores son simulados.
- Se agrega un supuesto nuevo, el llenado medio al recoger, y deja de usarse el 13 % como base de la carga fuera del ASE 5.
- El extracto de los operadores no puede ir en el release público de datos (ADR 0010) mientras la licencia siga sin verificar, así que `docker compose up` necesita descargarlo.
- Pendiente: la ubicación de los contenedores del ASE 5, los nodos de OSM de las bases de las ASE 1 a 4, la capacidad en kg de los compactadores de cargue lateral de cada operador y el texto de la Resolución CRA 1027 de 2026.
