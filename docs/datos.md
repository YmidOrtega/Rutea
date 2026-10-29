# Datos de la operación en Suba

Cifras que usa Rutea para simular la recolección en Suba (ASE 5), con su fuente. En el código viven en `rutea-app/.../scenario/SubaFigures.java` y en `rutea-geo/.../AreaSpec.java`; si cambia una, se actualizan los dos lugares en el mismo cambio.

Las cifras de la Superservicios salen del [informe de inspección a Área Limpia](https://www.superservicios.gov.co/sites/default/files/inline-files/Area-Limpia-Distrito-Capital-SAS-ESP.pdf) (visita del 9 al 13 de marzo de 2026), que compara el PGIRS de Bogotá con el PPSA del operador.

## Bogotá: localidades y ASE

Para elegir localidades y planificar por ASE (ADR 0012). En el código vive en `rutea-app/.../scenario/ServiceAreas.java`.

- **Reparto y operadores:** "Así está dividido el esquema de aseo en las localidades de Bogotá" ([Bogotá.gov.co, 7 feb. 2026](https://bogota.gov.co/mi-ciudad/habitat/como-esta-dividido-el-esquema-de-aseo-en-las-localidades-de-bogota)). Los nombres legales salen de la adjudicación ([Bogotá.gov.co, 4 ene. 2018](https://bogota.gov.co/mi-ciudad/habitat/adjudicado-nuevo-esquema-de-aseo-para-bogota)), que no lista localidades. El ASE 2 se adjudicó a "Limpieza Metropolitana S.A. ESP", que la página de 2026 llama LIME.
- **Relaciones de OSM:** son las `boundary=administrative` con `admin_level=8` del extracto `colombia-261019`. Su etiqueta `ref` coincide con el número oficial de cada una de las 20 localidades.

| ASE | Operador | Localidades (número oficial) | Base |
|---|---|---|---|
| 1 | Promoambiental Distrito | Usaquén (1), Chapinero (2), Santa Fe (3), San Cristóbal (4), Usme (5), La Candelaria (17), Sumapaz (20) | `PENDIENTE_VERIFICAR` |
| 2 | LIME | Tunjuelito (6), Bosa (7), Teusaquillo (13), Los Mártires (14), Antonio Nariño (15), Puente Aranda (16), Rafael Uribe Uribe (18), Ciudad Bolívar (19) | `PENDIENTE_VERIFICAR` |
| 3 | Ciudad Limpia | Kennedy (8), Fontibón (9) | `PENDIENTE_VERIFICAR` |
| 4 | Bogotá Limpia | Engativá (10), Barrios Unidos (12) | `PENDIENTE_VERIFICAR` |
| 5 | Área Limpia | Suba (11) | Av. Calle 17 # 124-81, Fontibón; nodo OSM 865490722 (ver abajo) |

| Localidad | Relación OSM | Localidad | Relación OSM |
|---|---:|---|---:|
| Usaquén | 2214885 | Suba | 2214884 |
| Chapinero | 1379547 | Barrios Unidos | 2214882 |
| Santa Fe | 4740232 | Teusaquillo | 2216632 |
| San Cristóbal | 4740231 | Los Mártires | 2216631 |
| Usme | 4740233 | Antonio Nariño | 2216654 |
| Tunjuelito | 2216689 | Puente Aranda | 2216655 |
| Bosa | 4740227 | La Candelaria | 2218276 |
| Kennedy | 4740229 | Rafael Uribe Uribe | 4740230 |
| Fontibón | 2216630 | Ciudad Bolívar | 4740228 |
| Engativá | 2214883 | Sumapaz | 5579632 |

- La base de cada operador sale de su informe de inspección de la Superservicios, como la de Área Limpia. Esos informes existen (la Superservicios lista uno de Promoambiental Distrito de 2026), pero el portal no deja descargarlos de forma automática; hay que bajarlos a mano. Ciudad Limpia publica en su sitio una base en la Calle 59C Sur # 51-50, pero en una página de hace unos cinco años, así que no se usa.
- Sumapaz es toda rural. Su modelo de recolección es `PENDIENTE_VERIFICAR`.

## Cifras con fuente

| Dato | Valor | Fuente | Uso en Rutea |
|---|---|---|---|
| Contenedores en vía pública en Bogotá | 10.746 | UAESP, [Bogotá.gov.co, 9 mar. 2022](https://bogota.gov.co/mi-ciudad/habitat/contenedores-soterrados-mejoraran-disposicion-de-residuos-en-bogota) | Base del número de contenedores |
| Reparto de los contenedores | "la mitad para residuos no aprovechables y la mitad para promover el reciclaje" | UAESP, [Bogotá.gov.co, 26 feb. 2025](https://bogota.gov.co/mi-ciudad/habitat/contenedores-de-basuras-en-bogota-apoyan-prestacion-servicio-aseo-2025) | F3 solo simula los negros |
| Servicio ordinario que entra a Doña Juana | ≈ 5.680 t/día (1.er trimestre de 2026, sin puntos críticos) | UAESP, [Bogotá.gov.co, 17 abr. 2026](https://bogota.gov.co/mi-ciudad/habitat/aumenta-la-produccion-de-residuos-solidos-en-bogota-segun-uaesp) | Demanda |
| Parte del ASE 5 en las toneladas de la ciudad | 15 % | PGIRS, en el informe de la Superservicios | Demanda y número de contenedores |
| Toneladas domiciliarias por cargue lateral (contenedores) | mínimo 13 % | PPSA de Área Limpia, en el informe de la Superservicios | Demanda (se usa el mínimo) |
| Frecuencia de recolección | de 3 a 6 veces por semana | Operador, en el informe de la Superservicios | 6 veces: carga de 7/6 días |
| Turnos | 06:00–14:00 y 18:00–02:00 (88 microrrutas cada uno), y otros menores | Operador, en el informe de la Superservicios | Turno de 8 h desde las 18:00 |
| Camiones y toneladas que entran a Doña Juana | 684 camiones y 6.368 t por día en promedio (≈ 9,3 t por camión) | [La República, 2025](https://www.larepublica.co/responsabilidad-social/control-de-emisiones-de-metano-tarea-pendiente-de-dona-juana-y-de-los-bogotanos-4070876) | Corrobora cargas de 8 t por viaje |
| Flota de compactadores | 17 de 32 yd (8 t), 2 de 27 yd (7 t), 8 de 25 yd (6 t), 1 de 16 yd (4 t); el operador declara 38 en total y el desglose suma 28 | Operador, en el informe de la Superservicios | Capacidad de 8.000 kg |
| Contenedores de no aprovechables en vía pública | de 3.200 L, 2.400 L y 1.100 L | Operador, en el informe de la Superservicios | F5 (sensores de llenado) |
| Contenedores en la licitación | de 1.000 a 2.500 L para no aprovechables; 3.270.000 L nominales en el ASE 5; se permiten "chips de llenado RFID" | [UAESP, Licitación 02 de 2017, Anexo 11](https://bogota.gov.co/sites/default/files/inline-files/anexo-11.-plan-de-contenerizacion_adenda20171124-1.pdf) | Contexto de los sensores. Los 3.270.000 L entre los 806 contenedores simulados darían 4.057 L cada uno, más que el máximo: el número de contenedores de Suba está subestimado o la capacidad nominal no se cumplió |
| Base de operaciones | Avenida Calle 17 # 124-81, La Estancia, Fontibón | Informe de la Superservicios | Sitio `base`: esquina Av. Calle 17 × Carrera 124, nodo OSM 865490722 |
| Sitio de disposición | Parque de Innovación Doña Juana, Av. Boyacá Km 5 Vía al Llano (PGIRS); Cl. 71 Sur # 3J-77 (PPSA; la Superservicios lo marca "No coincide") | Informe de la Superservicios | Sitio `relleno`: portería noreste, nodo OSM 1013564592 (`barrier=swing_gate`) |
| Centroide del ASE 5 y distancia al sitio de disposición | Av. Calle 145 con Carrera 84B; 32,589 km | PGIRS, en el informe de la Superservicios | Calibración del grafo (abajo) |

La dirección del PPSA para el relleno no sirve para ubicarlo: en OSM, la Calle 71 Sur y la Carrera 3J Este no se cruzan (sus nodos más cercanos están a 2,2 km) y quedan a 3,8 km del polígono del relleno.

### Emisiones

| Dato | Valor | Fuente | Uso en Rutea |
|---|---|---|---|
| Factor de emisión del gas/diésel oil en transporte por carretera | 74.100 kg CO₂/TJ (rango 72.600–74.800) | [IPCC 2006, vol. 2, cap. 3, Tabla 3.2.1](https://www.ipcc-nggip.iges.or.jp/public/2006gl/pdf/2_Volume2/V2_3_Ch3_Mobile_Combustion.pdf) | `DieselEmissions` |
| Poder calorífico neto del gas/diésel oil | 43,0 TJ/Gg (rango 41,4–43,3) | [IPCC 2006, vol. 2, cap. 1, Tabla 1.2](https://www.ipcc-nggip.iges.or.jp/public/2006gl/pdf/2_Volume2/V2_1_Ch1_Introduction.pdf) | `DieselEmissions` |

## Cifras derivadas

| Dato | Cálculo | Valor |
|---|---|---|
| Contenedores negros en Suba | 10.746 × 15 % × 1/2 | **806** |
| Kilos por día en contenedores de Suba | 5.680 t × 15 % × 13 % | **110.760 kg** |
| Carga media por contenedor y recolección | 110.760 × 7/6 ÷ 806 | **160 kg** |
| CO₂ por litro de diésel | 0,84 kg/L × 43,0 TJ/Gg × 74.100 kg/TJ | **2,68 kg** |

### Aprovechables

| Dato | Valor | Fuente | Uso en Rutea |
|---|---|---|---|
| Parte de lo recogido que se aprovecha | 16 % | [El Espectador, 18 sep. 2025](https://www.elespectador.com/bogota/a-diario-se-recogen-mas-de-5600-toneladas-de-residuos-no-aprovechables-en-bogota/), en la bajada y sin fuente explícita; `PENDIENTE_VERIFICAR` | kg por día en contenedores blancos |
| Inventario de ECA de Bogotá | Resoluciones UAESP 137 y 698 de 2023 | Citadas en conceptos de la Curaduría Urbana 3; el listado no está publicado | Pendiente: hoy la ECA es un supuesto |

## Supuestos (no son datos)

- Los contenedores se reparten entre las ASE como las toneladas (15 %). No hay un inventario por localidad publicado.
- Los contenedores se ubican al azar según la longitud de calle local, no según la vivienda: en el rectángulo de Suba OSM tiene 15.000 edificios mapeados, una cobertura incompleta y despareja para una localidad de cerca de 1,3 millones de habitantes.
- La carga de cada contenedor varía de forma uniforme entre 0,5 y 1,5 veces la media.
- Velocidades por tipo de vía (`RoadClass`): de 10 km/h en `living_street` a 60 km/h en autopista, para un compactador de noche.
- Las vías privadas solo entran al grafo cerca de un sitio: las del relleno son privadas para el público, no para los compactadores.
- `PENDIENTE_VERIFICAR`: **150 kg/m³ de residuos sueltos en el contenedor**. No hay cifra publicada para Bogotá: el estudio de caracterización de la UAESP (Consorcio NCU, 2017) reporta producción per cápita, no densidad. 150 kg/m³ es el valor de manual para "basura suelta en recipientes" (Tchobanoglous, 1993), citado en el [estudio de caracterización de CONAMA RM y la PUCV (2006)](https://santiagorecicla.mma.gob.cl/wp-content/uploads/2016/04/infor_residuos_conama2011.pdf). `SensorBenchmark` mide qué pasa con 100 y 200 kg/m³.
- Supuesto: **cada contenedor tiene uno de los tres tamaños que declara el operador, al azar**. No se conoce la mezcla.
- Supuesto: **la generación es pareja a lo largo del día**. No se encontró una curva horaria de presentación de residuos para Bogotá, y una inventada no sería honesta.
- **Patrón por día:** se recoge de lunes a sábado (6 veces por semana), así que el lunes cada contenedor carga dos días. Sale de la frecuencia declarada, no es un supuesto aparte.
- **Patrón por zona:** el estudio de la UAESP (2017) midió la producción per cápita por estrato y localidad, pero la tabla no está publicada en lo que se encontró (solo el promedio de la ciudad, 0,855 kg/hab-día). Sin ella, los sensores no distinguen zonas.
- `PENDIENTE_VERIFICAR`: **60 s detenido en cada contenedor**. No hay estudio publicado para Bogotá. Los fabricantes anuncian de 6 a 10 s por ciclo del brazo de cargue lateral ([Heil](https://www.trucknews.com/transportation/heil-takes-out-the-trash/1003117152), [Bucher](https://prod.buchermunicipal.com/int/node/111)), pero eso es solo una cota inferior: la parada incluye también acercarse, alinearse y revisar.
- Densidad del diésel: 0,84 kg/L, el valor típico; no es un dato verificado del ACPM colombiano.
- `PENDIENTE_VERIFICAR`: **0,8 L de diésel por km** para un compactador en recolección. No hay cifra publicada para Bogotá. Como el CO₂ sale proporcional a los km, el porcentaje de ahorro entre dos planes no depende de este valor; las toneladas absolutas sí.
- Todo el ACPM se cuenta como diésel fósil: su fracción de biodiésel no se descuenta (`PENDIENTE_VERIFICAR`: porcentaje de mezcla vigente). Esto sobreestima un poco el CO₂ fósil.
- `PENDIENTE_VERIFICAR`: **descarga en Doña Juana de 40 a 90 min**. "En promedio, gastan entre 40 minutos a una hora y media" ([El Tiempo, 3 jun. 2022](https://www.eltiempo.com/bogota/dona-juana-las-jugadas-del-operador-cgr-para-quedarse-en-relleno-sanitario-677588)). Es prensa, no un dato oficial. El plan de demo usa 40 min; la comparación muestra los dos extremos.
- Sitios sensibles (R02, R07): 644 sitios de OSM alrededor de Suba (479 centros educativos, 115 hospitales y clínicas, 10 centros asistenciales y 40 hoteles), extraídos con `mvn -pl rutea-app exec:java@sensitive-sites`. La cobertura de OSM no es completa: puede faltar algún colegio. Las interpretaciones están en `docs/normativa.md`.
- Aprovechables (`PENDIENTE_VERIFICAR` todo): 806 contenedores blancos (la otra mitad, dato UAESP); 21.100 kg por día, que salen de aplicar 16/84 a lo que pasa por los negros; la ECA en el vértice más cercano al centro de Suba, porque no hay ECA ubicadas ni en OSM ni en datos públicos; vehículos sin compactación de 1.500 kg; 20 min de descarga en la ECA; mismo turno que los compactadores. Las cifras muestran la mecánica de dos flotas, no sirven para decidir.
- `PENDIENTE_VERIFICAR`: **15 min de despacho de un camión de reserva** tras una avería (avisar, alistar tripulación y vehículo). Sin dato del operador.
- Cada compactador puede encadenar hasta 3 viajes en su turno de 8 h: base → viaje → descarga → viaje → descarga → base. Del segundo viaje en adelante sale de la portería, en el sentido que mide menos.

## Calibración del grafo

Desde el centroide del PGIRS (Calle 145 cerca de la Carrera 84B, nodo OSM 5150775121) hasta la portería, el grafo da **29,1 km** de ida y 29,7 km de vuelta, frente a los **32,589 km** del PGIRS: un 10,7 % menos. No se sabe cómo midió el PGIRS (qué entrada, qué ruta, si incluye el recorrido interno hasta el frente de descarga), así que no se ajusta nada para que cuadre.

## Pendiente

- Población de Suba frente a la de Bogotá en la serie oficial DANE–SDP–RMBC (agosto de 2025). Hoy no se usa: se usa el 15 % del PGIRS.
- Inventario oficial de contenedores de Suba (UAESP o Área Limpia).
- Bases de los operadores de las ASE 1 a 4, con dirección y nodo de OSM (informes de inspección de la Superservicios).
