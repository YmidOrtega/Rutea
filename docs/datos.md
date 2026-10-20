# Datos de la operación en Suba

Cifras que usa Rutea para simular la recolección en Suba (ASE 5), con su fuente. En el código viven en `rutea-app/.../scenario/SubaFigures.java` y en `rutea-geo/.../AreaSpec.java`; si cambia una, se actualizan los dos lugares en el mismo cambio.

Las cifras de la Superservicios salen del [informe de inspección a Área Limpia](https://www.superservicios.gov.co/sites/default/files/inline-files/Area-Limpia-Distrito-Capital-SAS-ESP.pdf) (visita del 9 al 13 de marzo de 2026), que compara el PGIRS de Bogotá con el PPSA del operador.

## Cifras con fuente

| Dato | Valor | Fuente | Uso en Rutea |
|---|---|---|---|
| Contenedores en vía pública en Bogotá | 10.746 | UAESP, [Bogotá.gov.co, 9 mar. 2022](https://bogota.gov.co/mi-ciudad/habitat/contenedores-soterrados-mejoraran-disposicion-de-residuos-en-bogota) | Base del número de contenedores |
| Reparto de los contenedores | "la mitad para residuos no aprovechables y la mitad para promover el reciclaje" | UAESP, [Bogotá.gov.co, 26 feb. 2025](https://bogota.gov.co/mi-ciudad/habitat/contenedores-de-basuras-en-bogota-apoyan-prestacion-servicio-aseo-2025) | F3 solo simula los negros |
| Servicio ordinario que entra a Doña Juana | ≈ 5.680 t/día (1.er trimestre de 2026, sin puntos críticos) | UAESP, [Bogotá.gov.co, 17 abr. 2026](https://bogota.gov.co/mi-ciudad/habitat/aumenta-la-produccion-de-residuos-solidos-en-bogota-segun-uaesp) | Demanda |
| Parte del ASE 5 en las toneladas de la ciudad | 15 % | PGIRS, en el informe de la Superservicios | Demanda y número de contenedores |
| Toneladas domiciliarias por cargue lateral (contenedores) | mínimo 13 % | PPSA de Área Limpia, en el informe de la Superservicios | Demanda (se usa el mínimo) |
| Frecuencia de recolección | de 3 a 6 veces por semana | Operador, en el informe de la Superservicios | 6 veces: carga de 7/6 días |
| Turnos | 06:00–14:00 y 18:00–02:00 (88 microrrutas cada uno), y otros menores | Operador, en el informe de la Superservicios | F4 (turnos) |
| Flota de compactadores | 17 de 32 yd (8 t), 2 de 27 yd (7 t), 8 de 25 yd (6 t), 1 de 16 yd (4 t); el operador declara 38 en total y el desglose suma 28 | Operador, en el informe de la Superservicios | Capacidad de 8.000 kg |
| Contenedores de no aprovechables en vía pública | de 3.200 L, 2.400 L y 1.100 L | Operador, en el informe de la Superservicios | F4 (capacidad por contenedor) |
| Base de operaciones | Avenida Calle 17 # 124-81, La Estancia, Fontibón | Informe de la Superservicios | Sitio `base`: esquina Av. Calle 17 × Carrera 124, nodo OSM 865490722 |
| Sitio de disposición | Parque de Innovación Doña Juana, Av. Boyacá Km 5 Vía al Llano (PGIRS); Cl. 71 Sur # 3J-77 (PPSA; la Superservicios lo marca "No coincide") | Informe de la Superservicios | Sitio `relleno`: portería noreste, nodo OSM 1013564592 (`barrier=swing_gate`) |
| Centroide del ASE 5 y distancia al sitio de disposición | Av. Calle 145 con Carrera 84B; 32,589 km | PGIRS, en el informe de la Superservicios | Calibración del grafo (abajo) |

La dirección del PPSA para el relleno no sirve para ubicarlo: en OSM, la Calle 71 Sur y la Carrera 3J Este no se cruzan (sus nodos más cercanos están a 2,2 km) y quedan a 3,8 km del polígono del relleno.

## Cifras derivadas

| Dato | Cálculo | Valor |
|---|---|---|
| Contenedores negros en Suba | 10.746 × 15 % × 1/2 | **806** |
| Kilos por día en contenedores de Suba | 5.680 t × 15 % × 13 % | **110.760 kg** |
| Carga media por contenedor y recolección | 110.760 × 7/6 ÷ 806 | **160 kg** |

## Supuestos (no son datos)

- Los contenedores se reparten entre las ASE como las toneladas (15 %). No hay un inventario por localidad publicado.
- Los contenedores se ubican al azar según la longitud de calle local, no según la vivienda: en el rectángulo de Suba OSM tiene 15.000 edificios mapeados, una cobertura incompleta y despareja para una localidad de cerca de 1,3 millones de habitantes.
- La carga de cada contenedor varía de forma uniforme entre 0,5 y 1,5 veces la media.
- Velocidades por tipo de vía (`RoadClass`): de 10 km/h en `living_street` a 60 km/h en autopista, para un compactador de noche.
- Las vías privadas solo entran al grafo cerca de un sitio: las del relleno son privadas para el público, no para los compactadores.

## Calibración del grafo

Desde el centroide del PGIRS (Calle 145 cerca de la Carrera 84B, nodo OSM 5150775121) hasta la portería, el grafo da **29,1 km** de ida y 29,7 km de vuelta, frente a los **32,589 km** del PGIRS: un 10,7 % menos. No se sabe cómo midió el PGIRS (qué entrada, qué ruta, si incluye el recorrido interno hasta el frente de descarga), así que no se ajusta nada para que cuadre.

## Pendiente

- Población de Suba frente a la de Bogotá en la serie oficial DANE–SDP–RMBC (agosto de 2025). Hoy no se usa: se usa el 15 % del PGIRS.
- Inventario oficial de contenedores de Suba (UAESP o Área Limpia).
