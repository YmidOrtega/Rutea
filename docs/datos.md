# Datos de la operación en Suba

Cifras que usa Rutea para simular la recolección en Suba (ASE 5), con su fuente. En el código, las de toda Bogotá y los supuestos generales viven en `rutea-app/.../scenario/BogotaFigures.java`; la base de cada ASE, en su `ServiceArea`, y las cifras del operador (participación, cargue lateral, frecuencia, turno, compactador y contenedores), en su `Operation`, las dos en `ServiceAreas.java`; y los sitios del grafo, en `rutea-geo/.../AreaSpec.java`. Si cambia una, se actualizan todos los lugares en el mismo cambio.

**Nombres de los operadores.** Rutea no usa la razón social de las empresas prestadoras: cada una lleva el nombre de un río de su zona. Arzobispo es el operador del ASE 1, Tunjuelo el del ASE 2, Fucha el del ASE 3, Salitre el del ASE 4 y Torca el del ASE 5. Las fuentes se siguen citando con su enlace, el documento y la fecha, y algunos enlaces llevan el nombre de la empresa en su dirección. Una cita textual que la nombra lo reemplaza por `[el operador]`.

Las cifras de la Superservicios salen del [informe de inspección a Torca](https://www.superservicios.gov.co/sites/default/files/inline-files/Area-Limpia-Distrito-Capital-SAS-ESP.pdf) (visita del 9 al 13 de marzo de 2026), que compara el PGIRS de Bogotá con el PPSA del operador.

## Bogotá: localidades y ASE

Para elegir localidades y planificar por ASE (ADR 0012). En el código vive en `rutea-app/.../scenario/ServiceAreas.java`.

- **Reparto y operadores:** "Así está dividido el esquema de aseo en las localidades de Bogotá" ([Bogotá.gov.co, 7 feb. 2026](https://bogota.gov.co/mi-ciudad/habitat/como-esta-dividido-el-esquema-de-aseo-en-las-localidades-de-bogota)). El ASE 2 se adjudicó a "Limpieza Metropolitana S.A. ESP", que la página de 2026 llama Tunjuelo.
- **Relaciones de OSM:** son las `boundary=administrative` con `admin_level=8` del extracto `colombia-261019`. Su etiqueta `ref` coincide con el número oficial de cada una de las 20 localidades.

| ASE | Operador | Localidades (número oficial) | Base |
|---|---|---|---|
| 1 | Arzobispo | Usaquén (1), Chapinero (2), Santa Fe (3), San Cristóbal (4), Usme (5), La Candelaria (17), Sumapaz (20) | Carrera 60 # 15-07, Puente Aranda (informes de la Superservicios, marzo de 2026, y de la UAESP, agosto de 2026); nodo OSM 8483428972 |
| 2 | Tunjuelo | Tunjuelito (6), Bosa (7), Teusaquillo (13), Los Mártires (14), Antonio Nariño (15), Puente Aranda (16), Rafael Uribe Uribe (18), Ciudad Bolívar (19) | Carrera 62 # 19-04 Int. 4, Puente Aranda (informe de la Superservicios, marzo de 2026); nodo OSM 3868073372 |
| 3 | Fucha | Kennedy (8), Fontibón (9) | Calle 59C Sur # 51-50, Ciudad Bolívar (informes de la Superservicios, marzo de 2026, y de la UAESP, agosto de 2026); nodo OSM 6580796244 |
| 4 | Salitre | Engativá (10), Barrios Unidos (12) | Calle 63 # 96-05, Engativá (informe de la Superservicios, marzo de 2026); nodo OSM 5769034872 |
| 5 | Torca | Suba (11) | Av. Calle 17 # 124-81, Fontibón; nodo OSM 865490722 (ver abajo) |

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

### Cifras por ASE

| ASE | Suelo urbano | Contenedores exigidos, no aprovechables | Centroide | Centroide → Doña Juana | Contenedores negros en Rutea (supuesto) |
|---|---:|---:|---|---:|---:|
| 1 | 9.044,4 ha (24 %) | 1.160.000 L | Cra. 3 con Calle 75 | 27,848 km | 1.290 |
| 2 | 11.834,7 ha (31 %) | 2.410.000 L | AK 68 # 41A-46 Sur | 9,74 km | 1.666 |
| 3 | 6.659,2 ha (18 %) | 2.390.000 L | Cra. 80F # 7A-39 | 14,4 km | 967 |
| 4 | 4.629,6 ha (12 %) | 3.440.000 L | Cra. 83 # 71B-21 | 21,5 km | 645 |
| 5 | 5.800,7 ha (15 %) | 3.270.000 L | Av. Calle 145 con Cra. 84B | 32,589 km | 806 |
| Bogotá | 37.969 ha | 12.670.000 L | | | 5.374 |

- **Suelo urbano:** "Cobertura de recolección área urbana", [Línea base del PGIRS de Bogotá, agosto de 2018](https://habitatbogota.gov.co/sites/default/files/archivos-adjuntos/LINEA%20BASE%20PGIRS%20OBS%20HABITAT%203.8.2018.pdf), Tabla 3, parámetro 1: la intersección del suelo urbano con cada ASE. Es el 15 % que la inspección de la Superservicios cita para el ASE 5 ("Torca corresponde al ASE 5 con el 15% del total de la ciudad"). Desde F3, Rutea lo había leído como parte de las toneladas: es parte del área.
- **Contenedores exigidos:** [UAESP, Licitación 02 de 2017, Anexo 11](https://bogota.gov.co/sites/default/files/inline-files/anexo-11.-plan-de-contenerizacion_adenda20171124-1.pdf), Tabla 4: capacidad nominal agregada, en litros, de los contenedores de no aprovechables de cada ASE.
- **Centroide y distancia a Doña Juana:** misma línea base, Tabla 3, parámetro 7, tomados de los estudios de costos de cada prestador (Resolución CRA 720 de 2015). Sirven para calibrar el grafo en cada ASE, como se hizo con el ASE 5 (abajo).
- **Contenedores negros en Rutea:** 10.746 × parte del suelo urbano × 1/2, el mismo supuesto que da los 806 de Suba (ver "Supuestos"). El ASE 2 es la más grande, con 1.666, por debajo de los ~1.700 en los que F3 previó el primer ajuste de escala del solver.
- **Las dos participaciones no coinciden.** El ASE 5 tiene el 15 % del suelo urbano, pero le corresponde el 25,8 % de los litros exigidos; el ASE 1 tiene el 24 % del suelo y el 9,2 % de los litros. Cuál aproxima mejor los contenedores reales es una decisión abierta: cambiarla movería todas las cifras de Suba publicadas desde F3.
- **Toneladas por ASE** (informes de la Superservicios, marzo de 2026). Se miden en meses distintos, y no está claro que todas cuenten lo mismo (domiciliarias o con barrido, poda y césped). El ASE 5 queda `PENDIENTE_VERIFICAR`.

  | ASE | t/día | Parte de las 5.680 t/día de la ciudad | Suelo urbano |
  |---|---:|---:|---:|
  | 1 | ≈ 1.169 (36.230,27 t en enero de 2026) | 20,6 % | 24 % |
  | 2 | 1.804,7 | 31,8 % | 31 % |
  | 3 | ≈ 1.075 (33.316,12 t en enero de 2026) | 18,9 % | 18 % |
  | 4 | 725,1 | 12,8 % | 12 % |

  Salvo en el ASE 1, el suelo urbano aproxima bien la parte de las toneladas.
- **Bases y flotas:** las cinco bases y las flotas de las ASE 1 a 4 tienen fuente (ver "Inventario de fuentes por ASE").
- **Nodo de cada base:** las bases de las ASE 1 a 4 están mapeadas en OSM, con el nombre del operador. El nodo es donde la vía del predio sale a la calle pública. Si no hay vía mapeada, es el punto de la calle más cercano al edificio. La localidad se comprobó con los polígonos de OSM.

  | ASE | Predio en OSM | Nodo | Dónde |
  |---|---|---:|---|
  | 1 | Vía 458791813, con el nombre del operador (`building=industrial`) | 8483428972 | Salida a la Carrera 60, a unos 8 m de la esquina con la Calle 15 |
  | 2 | Vía 881770228, con el nombre del operador (`landuse=industrial`, `addr:street=Carrera 62`, `addr:housenumber=19-04`) | 3868073372 | Salida del patio a la Carrera 62 con Calle 20 |
  | 3 | Vía 700784521, con el nombre del operador (`landuse=industrial`) | 6580796244 | Salida del patio a la Calle 59C Sur (Av. Madelena). Queda en Ciudad Bolívar, fuera del ASE 3 |
  | 4 | Vía 1210674921, "Bogota Limpia ESP SAS" (`building=yes`, `addr:street=Avenida Calle 63`) | 5769034872 | Calzada sur de la Av. Calle 63, a unos 31 m del edificio; no hay vía de acceso mapeada |
- Sumapaz es toda rural. Su modelo de recolección es `PENDIENTE_VERIFICAR`.

### Inventario de fuentes por ASE (consultado el 2026-10-29)

Lo que publican la UAESP y los operadores sobre la operación de 2026. Rutea todavía no usa estas cifras: cambiar los contenedores simulados por estas cifras es una decisión aparte.

**La contenerización cambió con la prórroga de 2026.** Los contratos de concesión de 2018 se prorrogaron en febrero de 2026; el del ASE 5 se firmó el 6 de febrero. La UAESP pidió a cada operador actualizar su Plan de Contenerización, y todos están bajando la capacidad nominal de no aprovechables. Lo que sale de la calle pasa a recolección puerta a puerta. Los contenedores aprovechables están "entregados a terceros", en comodato con organizaciones de recicladores. La cifra de 10.746 contenedores (2022) ya no describe la ciudad.

| ASE | Contenedores negros en la calle | Plan de Contenerización de 2026 | Recolección publicada | Base |
|---|---|---|---|---|
| 1 · Arzobispo | 168 "en campo" (ArcGIS) | 242 de 1.100 L, con observaciones de la interventoría | 41 macrorrutas y 239 microrrutas (Superservicios); 302 polígonos en su ArcGIS, 212 domiciliarios | Carrera 60 # 15-07 |
| 2 · Tunjuelo | 990 de 2.200 L, con ubicación (ArcGIS, 10 de octubre de 2026) | Con observaciones, escalado a la UAESP | 24 macrorrutas y 352 microrrutas (Superservicios); 9 macrorrutas publicadas en su ArcGIS | Carrera 62 # 19-04 Int. 4 |
| 3 · Fucha | 0 de 2.400 L (ArcGIS) | 91 de 1.100 L, aprobado | 15 macrorrutas y 432 microrrutas (Superservicios); puerta a puerta | Calle 59C Sur # 51-50 |
| 4 · Salitre | 763 de 2.400 L, todos en Engativá (ArcGIS) | Sin claridad en el informe de la UAESP | 12 macrorrutas y 99 microrrutas (Superservicios); 10 publicadas en su ArcGIS, con cargue | Calle 63 # 96-05 |
| 5 · Torca | 374 del contrato y 70 de adiciones, a julio | **320**, aprobado | – | Av. Calle 17 # 124-81 (arriba) |

**Fuentes de la UAESP.** Los [informes mensuales de supervisión, monitoreo y control](https://www.uaesp.gov.co/content/informes-supervision-y-control-rbl) de agosto de 2026, uno por ASE, que resumen los de la interventoría (contrato 396 de 2018). Los anexos que citan (inventario de vehículos, inventario de contenedores, planes operativos) no se publican. Los planes de supervisión de 2026 de la misma página son calendarios de verificación, sin cifras operativas.

- **ASE 1** ([informe](https://www.uaesp.gov.co/sites/default/files/documentos/ASE_1_Informe_de_Supervision_Monitoreo_y_Control_AGOSTO_2026.pdf), contrato 283 de 2018):
  - El operador presentó el plan actualizado el 13 de abril de 2026, con 242 contenedores de 1.100 L, y la interventoría lo observó el 16 de abril.
  - "Se retiraron del espacio público 484 contenedores de material no aprovechable, los cuales se encuentran resguardados en la KR 60 # 15 07 base de operaciones [el operador]".
  - Los 484 aprovechables fueron entregados a terceros.
- **ASE 2** ([informe](https://www.uaesp.gov.co/sites/default/files/documentos/ASE_2_Informe_de_Supervision_Monitoreo_y_Control_AGOSTO_2026.pdf), contrato 284 de 2018):
  - El Anexo 11 le fija 2.410.000 L, que el operador cubrió con 1.096 contenedores de 2.200 L e igual número de aprovechables de 1.100 L (1.092 entregados a terceros).
  - A junio de 2026 retiró 95 no aprovechables.
- **ASE 3** ([informe](https://www.uaesp.gov.co/sites/default/files/documentos/ASE_3_Informe_de_Supervision_Monitoreo_y_Control_AGOSTO_2026.pdf), contrato 285 de 2018):
  - Instaló 996 contenedores de 2.400 L hasta febrero de 2019.
  - El plan del 25 de marzo de 2026 fija 91 de 1.100 L "a través del retiro de la totalidad de contenedores de 2400 litros". La interventoría dio concepto favorable el 22 de abril de 2026.
  - El inventario relaciona 214 retirados por el plan piloto y 748 por la actualización del plan.
- **ASE 4** ([informe](https://www.uaesp.gov.co/sites/default/files/documentos/ASE_4_Informe_de_Supervision_Monitoreo_y_Control_AGOSTO_2026.pdf)): de 1.435 contenedores instalados, 1.429 fueron entregados a terceros. Hay varias solicitudes de acción correctiva abiertas por capacidad nominal.
- **ASE 5** ([informe](https://www.uaesp.gov.co/sites/default/files/documentos/ASE_5_Informe_de_Supervision_Monitoreo_y_Control_AGOSTO_2026.pdf), contrato 287 de 2018):
  - A julio de 2026 reporta 1.211 contenedores: 374 no aprovechables del contrato principal, 767 aprovechables entregados a terceros y 70 no aprovechables de adiciones (30 soterrados y 40 superficiales).
  - El plan actualizado (2 de marzo de 2026) "estableció una capacidad nominal de 320 contenedores para residuos no aprovechables", con concepto favorable de la interventoría el 20 de mayo de 2026.
  - En agosto, la interventoría encontró 64 contenedores adicionales "Ubicado en Campo" y 53 registros duplicados.

**Fuentes de la Superservicios.** Los informes de inspección de marzo de 2026 a los cinco operadores, del mismo tipo que el de Torca (arriba). Los de las ASE 1 a 4 se descargaron el 2026-10-29. Las flotas que declaran los operadores en la visita no coinciden con las del SUI, y la Superservicios lo marca en cada caso como presunto incumplimiento.

- **ASE 1, Arzobispo** ([informe](https://www.superservicios.gov.co/sites/default/files/inline-files/Informe-Promoambiental-Distrito-SAS-ESP.pdf), visita del 2 al 6 de marzo de 2026):
  - **Base:** "Base de operaciones. Dirección: Carrera 60 No. 15 – 07 Puente Aranda". Es la misma KR 60 # 15-07 donde el informe de la UAESP dice que se guardan los contenedores retirados.
  - **Rutas:** 41 macrorrutas y 239 microrrutas, con frecuencia de 3 y 7 veces por semana.
  - **Flota:** 79 compactadores de 10 a 28 yd³, de los cuales 23 son de reserva y 3 de carga lateral para contenedores; además, 14 ampliroll de 15 yd³ y volquetas.
  - **Toneladas:** "la cantidad de residuos provenientes del ASE 1, para el mes de enero de 2026 suma 36.230,27 toneladas", unas 1.169 t/día.
- **ASE 2, Tunjuelo** ([informe](https://www.superservicios.gov.co/sites/default/files/inline-files/Informe-inspeccion-Limpieza-Metropolitana-SA-ESP-Lime.pdf), visita del 2 al 6 de marzo de 2026):
  - **Base:** "La base de operaciones se encuentra ubicada en la Carrera 62 19 - 04 INT. 4". La portada del informe dice "Carrera 69 No. 19 – 04 INT 4"; se toma la del texto, que coincide con los canales de atención publicados por la [Alcaldía](https://bogota.gov.co/mi-ciudad/habitat/canales-de-atencion-de-los-operadores-de-aseo-en-bogota-2026).
  - **Rutas:** 24 macrorrutas y 352 microrrutas. Frecuencia de 3 y 6 veces por semana en zona residencial, y de 6 y 7 en zona comercial.
  - **Horarios:** de 06:00–16:00 a 18:30–04:30, con varios turnos intermedios.
  - **Flota declarada en la visita:**
    - 92 compactadores;
    - 5 compactadores de cargue lateral de 32 yd³;
    - 68 compactadores dobles de 25 yd³;
    - 18 compactadores sencillos de 14 yd³;
    - 1 recolector compactador de 32 yd³;
    - 20 volquetas dobles.

    En el SUI figuran 191 compactadores activos y 69 volquetas (actualización del 22 de abril de 2026). La Superservicios lo marca como presunto incumplimiento por no actualizar el SUI.
  - **Toneladas:** "en promedio 54.892 toneladas de residuos mensuales provenientes del ASE2, lo cual significa un promedio diario de 1804.7 toneladas". Es la primera cifra de toneladas por ASE con fuente. Frente a las 5.680 t/día de la ciudad da el 31,8 %, cerca del 31 % de suelo urbano del ASE 2.
- **ASE 3, Fucha** ([informe](https://www.superservicios.gov.co/sites/default/files/inline-files/Informe-concreto-Ciudad-Limpia-SA-ESP.pdf), visita del 2 al 6 de marzo de 2026):
  - **Base:** "La base de operaciones del prestador se encuentra localizada en la calle 59c sur # 51 - 50". Allí está también su sede administrativa, y la UAESP la visitó el 26 de agosto de 2026 ("CL 59C SUR 51 50, Base de operaciones").
  - **Rutas:** 15 macrorrutas y 432 microrrutas. La frecuencia es de 3 veces por semana, 7 para algunos grandes productores y 6 para la atención de contenedores.
  - **Flota en operación:** 3 compactadores de 16 yd³, 30 de 25 yd³ y 7 de 28 yd³, más 3 de reserva. Compró 9 compactadores nuevos (modelo 2026), de los que uno ya opera. El SUI registra 41.
  - **Toneladas, enero de 2026:** 33.316,12 t en total, de las cuales 30.795,80 son domiciliarias (unas 1.075 t/día en total).
- **ASE 4, Salitre** ([informe](https://www.superservicios.gov.co/sites/default/files/inline-files/Informe-inspeccion-Bogota-Limpia-SAS-ESP.pdf), visita del 9 al 13 de marzo de 2026):
  - **Base:** "ubicada en la Calle 63 Nº 96-05 de la localidad de Engativá", en un predio de 19.652 m². La portada del informe dice "Av. Calle 63 No. 96 - 05, Fontibón"; se toma la del texto, porque la Calle 63 con Carrera 96 queda en Engativá.
  - **Rutas:** 12 macrorrutas y 99 microrrutas. La frecuencia es de 3 veces por semana en zona residencial y diaria en zona comercial.
  - **Horarios:** 06:00–15:00, 18:00–03:00 y 14:00–22:00.
  - **Flota:** 48 vehículos de recolección: 30 compactadores de 16 a 25 yd³, 13 volquetas y 5 ampliroll. El SUI registra 48 compactadores.
  - **Toneladas:** "en promedio 21.753 toneladas de residuos mensuales provenientes de la ASE4 y un promedio diario de 725,1 toneladas".

**Fuentes de los operadores.** Visores públicos de ArcGIS Online, leídos con la API REST pública (`/query`). Ninguna capa declara licencia: `PENDIENTE_VERIFICAR` antes de versionar un extracto. Los contenedores negros "en campo" de las ASE 1, 2 y 4 se descargan con `mvn -pl rutea-app exec:java@operator-containers` (ver `data/ase/README.md`).

- **ASE 1, Arzobispo** ([visor](https://experience.arcgis.com/experience/ce3b02ef43e440479c946a0da02f331e/page/Recolecci%C3%B3n?org=promodistrito1)):
  - **Contenedores** (`C0nt3n3d0r3s`): 1.168 registros. 168 "En campo", todos no aprovechables, con frecuencia de 3 veces por semana:

    | Localidad | De 1.100 L | De 2.200 L |
    |---|---:|---:|
    | Usme | 107 | – |
    | Usaquén | 46 | 11 |
    | Chapinero | – | 2 |
    | San Cristóbal | – | 2 |

    Los otros 1.000 están "Retirado a Base de Operaciones" (532) o "Entregado a terceros" (468).
  - **Microrrutas** (`p_m1cr0rrut4_r3c0l3cc10n`): 302 polígonos, versiones V16 vigentes de abril a octubre de 2026. Cada uno trae frecuencia, días, turno, horario, tipo de vehículo y km de recolección.
    - **Domiciliarias:** 212. De ellas, 167 son de 3 veces por semana, en turno de 06:00–16:00 o de 18:00–04:00.
    - **Vehículos:** compactadores de 25 yd (14 t) y de 16–17 yd (8,5 t).
    - **Recorridos:** la capa `l_r3c0rr1d0_m1cr0rrut4_r3c0l3cc10n` trae el trazado de cada microrruta; no se ha revisado.
- **ASE 3, Fucha** ([visor de recolección](https://climpia.maps.arcgis.com/apps/webappviewer/index.html?id=aa38dd9a651a448e9ff6008ba6cda087), [visor de contenedores](https://climpia.maps.arcgis.com/apps/webappviewer/index.html?id=dde1f3748589406fb0d0da03b2ab0e75)):
  - **Contenedores** (`Pagina_Web_Contenedores_2021`, editada el 4 de septiembre de 2026): ninguno de los 996 no aprovechables sigue en la calle. 747 figuran retirados "por descontenerización" y 248 "por prueba piloto"; las cifras no coinciden del todo con las del informe de la UAESP. Los 996 aprovechables están "entregados a terceros".
  - **Macrorrutas:** las de 2026 (`PuntosxCuartelillo`, capa 1) son 14 polígonos por cuartelillo (B1 a B12, I6), con turno de día, tarde o noche. El visor de recolección todavía usa las de 2019.
  - **Puerta a puerta:** la nota "Avanza retiro de contenedores en Fontibón y Kennedy" ([SIGAB, 23 sep. 2026](https://sigab.gov.co/avanza-retiro-de-contenedores-en-fontibon-y-kennedy/)) informa más de 550 contenedores retirados desde finales de abril, el paso a recolección puerta a puerta y su base: la Resolución CRA 1027 de 2026, que modifica el Contrato 285 de 2018. El texto de la resolución es `PENDIENTE_VERIFICAR`.
- **ASE 4, Salitre** ([visor](https://bogotalimpia.maps.arcgis.com/apps/webappviewer/index.html?id=13724c8b7e444e119179d0412f43a92e)):
  - **Contenedores negros** (`Contenerizacion_ASE4_2400_Actualizacion`, editada el 14 de septiembre de 2026): 1.935 registros de 2.400 L, con dirección y microrruta.

    | Estado | Contenedores |
    |---|---:|
    | En ruta (todos en Engativá; ninguno en Barrios Unidos) | 763 |
    | Retirados | 493 |
    | De baja | 503 |
    | En bodega | 176 |
    | **Total** | **1.935** |
  - **Contenedores blancos** (`Contenerizacion_ASE4_1100_Actualizacion`): 3.194 registros de 1.100 L. 1.413 están en ruta, entregados en comodato.
  - **Macrorrutas** (`Macro_Rec_Dom_20260501`): 10 polígonos vigentes desde el 1 de mayo de 2026.
    - **Turnos:** de mañana (06:00–15:00) y de noche (18:00–03:00).
    - **Frecuencias:** lunes-miércoles-viernes, martes-jueves-sábado, o de lunes a sábado.
    - **Cargue:** lateral solo en Engativá; en Barrios Unidos todo es manual.
- **ASE 2, Tunjuelo** ([visor](https://experience.arcgis.com/experience/7a9ef52ad80746f2b996dda11b954e3f/)):
  - **Contenedores** (`CONTENEDORES_OCTUBRE_2026`, corte del 10 de octubre de 2026): 2.086 registros, con dirección, UPZ, microrruta y turno. 990 no aprovechables de 2.200 L "en campo", que cuadran con los 1.096 del contrato menos los 95 retirados que reporta la UAESP. Los aprovechables son 1.092 "entregados a terceros" y 4 en campo.

    | Localidad | Contenedores negros en campo |
    |---|---:|
    | Ciudad Bolívar | 350 |
    | Rafael Uribe Uribe | 251 |
    | Puente Aranda | 136 |
    | Bosa | 125 |
    | Tunjuelito | 98 |
    | Teusaquillo | 30 |
    | Los Mártires y Antonio Nariño | 0 |
  - **Macrorrutas** (`MICRORUTAS_RECOLECCIÓN_DOMICILIARIA_052024`, rotulada "2026" en el visor): 9 polígonos.
    - **Turnos:** día (06:00–16:00), tarde (14:00–23:00) y noche (18:30–04:30).
    - **Frecuencias:** lunes-miércoles-viernes, martes-jueves-sábado, de lunes a sábado, y domingo.
  - La capa de cuartelillos trae puntos de los equipos de barrido con el nombre del propietario de cada predio. No es la base de operaciones, y no se copia porque son datos personales.

## Población y toneladas por localidad (F8)

La demanda del puerta a puerta (ADR 0014) sale de las toneladas del servicio ordinario que entran a Doña Juana, repartidas por la población urbana de cada localidad.

- **Población:** población urbana ("Cabecera Municipal") proyectada para 2026, columna `TOTAL`, hoja "Localidades" del anexo del DANE [`anexo-proyecciones-poblacion-bogota-desagreacion-loc-2018-2035-UPZ-2018-2024.xlsx`](https://www.dane.gov.co/files/censo2018/proyecciones-de-poblacion/Bogota/anexo-proyecciones-poblacion-bogota-desagreacion-loc-2018-2035-UPZ-2018-2024.xlsx) (convenio 095 de 2020, actualizado en noviembre de 2021; SHA-256 del archivo descargado el 2026-10-29: `bb734f6f…`). Existe una actualización de 2025, del Contrato Interadministrativo 500 entre el DANE, la SDP y la Región Metropolitana, que el Observatorio de Salud de Bogotá cita, pero no se encontró publicada por localidad. Cuando aparezca, reemplaza esta tabla.
- **Toneladas:** las 5.680 t/día del servicio ordinario que entran a Doña Juana (primer trimestre de 2026; ver "Cifras con fuente"), las mismas que ya usa Rutea (`BogotaFigures.BOGOTA_ORDINARY_TONNES_PER_DAY`). Incluyen lo que recogen los contenedores; el puerta a puerta es el resto.
- **Supuesto:** cada habitante produce lo mismo en cualquier localidad. La UAESP midió la producción por estrato y localidad en 2017, pero esa tabla no está publicada (ver "Patrón por zona" en "Supuestos").

| N.º | Localidad | Población urbana 2026 | Parte | t/día (5.680 × parte) |
|---:|---|---:|---:|---:|
| 1 | Usaquén | 610.405 | 7,50 % | 426,2 |
| 2 | Chapinero | 186.028 | 2,29 % | 129,9 |
| 3 | Santa Fe | 107.300 | 1,32 % | 74,9 |
| 4 | San Cristóbal | 413.746 | 5,09 % | 288,9 |
| 5 | Usme | 419.136 | 5,15 % | 292,7 |
| 6 | Tunjuelito | 187.698 | 2,31 % | 131,1 |
| 7 | Bosa | 741.537 | 9,12 % | 517,8 |
| 8 | Kennedy | 1.045.353 | 12,85 % | 729,9 |
| 9 | Fontibón | 415.054 | 5,10 % | 289,8 |
| 10 | Engativá | 825.633 | 10,15 % | 576,5 |
| 11 | Suba | 1.347.097 | 16,56 % | 940,6 |
| 12 | Barrios Unidos | 161.766 | 1,99 % | 113,0 |
| 13 | Teusaquillo | 163.391 | 2,01 % | 114,1 |
| 14 | Los Mártires | 82.764 | 1,02 % | 57,8 |
| 15 | Antonio Nariño | 87.204 | 1,07 % | 60,9 |
| 16 | Puente Aranda | 260.371 | 3,20 % | 181,8 |
| 17 | La Candelaria | 19.207 | 0,24 % | 13,4 |
| 18 | Rafael Uribe Uribe | 395.986 | 4,87 % | 276,5 |
| 19 | Ciudad Bolívar | 664.702 | 8,17 % | 464,1 |
| | **Bogotá urbana** | **8.134.378** | **100 %** | **5.680,0** |

Sumapaz no tiene población urbana en la proyección.

## Cifras con fuente

| Dato | Valor | Fuente | Uso en Rutea |
|---|---|---|---|
| Contenedores en vía pública en Bogotá | 10.746 | UAESP, [Bogotá.gov.co, 9 mar. 2022](https://bogota.gov.co/mi-ciudad/habitat/contenedores-soterrados-mejoraran-disposicion-de-residuos-en-bogota) | Base del número de contenedores. Desactualizada: con la prórroga de 2026 cada ASE bajó su capacidad nominal (ver "Inventario de fuentes por ASE"); el ASE 5 tiene aprobados 320 contenedores negros |
| Reparto de los contenedores | "la mitad para residuos no aprovechables y la mitad para promover el reciclaje" | UAESP, [Bogotá.gov.co, 26 feb. 2025](https://bogota.gov.co/mi-ciudad/habitat/contenedores-de-basuras-en-bogota-apoyan-prestacion-servicio-aseo-2025) | F3 solo simula los negros |
| Servicio ordinario que entra a Doña Juana | ≈ 5.680 t/día (1.er trimestre de 2026, sin puntos críticos) | UAESP, [Bogotá.gov.co, 17 abr. 2026](https://bogota.gov.co/mi-ciudad/habitat/aumenta-la-produccion-de-residuos-solidos-en-bogota-segun-uaesp) | Demanda |
| Parte del suelo urbano de Bogotá que cubre el ASE 5 | 15 % (5.800,7 de 37.969 ha) | PGIRS, en el informe de la Superservicios y en su línea base de 2018 | Supuesto: se usa como parte de las toneladas y de los contenedores (ver "Supuestos") |
| Toneladas domiciliarias por cargue lateral (contenedores) | mínimo 13 % | PPSA de Torca, en el informe de la Superservicios | Demanda (se usa el mínimo) |
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

- Las toneladas y los contenedores se reparten entre las ASE como el suelo urbano (15 % en Suba). Las toneladas de las ASE 1 a 4 de la Superservicios confirman la aproximación, salvo en el ASE 1 (ver "Toneladas por ASE"). Los contenedores reales de 2026 ya tienen fuente (ver "Inventario de fuentes por ASE") y son muchos menos: Rutea sigue con este supuesto hasta que se decida el cambio.
- Los contenedores se ubican al azar según la longitud de calle local, no según la vivienda: en el rectángulo de Suba OSM tiene 15.000 edificios mapeados, una cobertura incompleta y despareja para una localidad de cerca de 1,3 millones de habitantes.
- La carga de cada contenedor varía de forma uniforme entre 0,5 y 1,5 veces la media.
- Velocidades por tipo de vía (`RoadClass`): de 10 km/h en `living_street` a 60 km/h en autopista, para un compactador de noche.
- Las vías privadas solo entran al grafo cerca de un sitio: las del relleno son privadas para el público, no para los compactadores.
- `PENDIENTE_VERIFICAR`: **150 kg/m³ de residuos sueltos en el contenedor**. No hay cifra publicada para Bogotá: el estudio de caracterización de la UAESP (Consorcio NCU, 2017) reporta producción per cápita, no densidad. 150 kg/m³ es el valor de manual para "basura suelta en recipientes" (Tchobanoglous, 1993), citado en el [estudio de caracterización de CONAMA RM y la PUCV (2006)](https://santiagorecicla.mma.gob.cl/wp-content/uploads/2016/04/infor_residuos_conama2011.pdf). `SensorBenchmark` mide qué pasa con 100 y 200 kg/m³.
- Supuesto: **cada contenedor tiene uno de los tres tamaños que declara el operador, al azar**. No se conoce la mezcla.
- `PENDIENTE_VERIFICAR`: **llenado medio al recoger del 100 %** (ADR 0013; `SideLoading`). La carga de cada contenedor real es litros × 150 kg/m³ × el llenado. No hay dato de llenado. Los planes usan la cota alta para no quedarse cortos de camiones, y el plan se reporta también con 50 y 75 %. El 13 % de toneladas por cargue lateral que declara Torca no acota el llenado: con los 320 contenedores aprobados haría falta un llenado del 84 % si todos fueran de 3.200 L, del 112 % si fueran de 2.400 L y del 245 % si fueran de 1.100 L, y no se publica la mezcla.
- **Suba de 2026** (`SubaNoche-n321-s1-f100`): los 320 contenedores aprobados en el Plan de Contenerización del ASE 5 no tienen ubicación publicada. Se ubican con el método de F3 y la misma semilla que los 806, cada uno con un tamaño al azar entre los tres que declara Torca (supuesto: no se publica la mezcla), en el turno de 18:00 a 02:00 de su `Operation`. Con llenado del 100 % recogen 110,5 t por recolección, 94,7 t/día con 6 recolecciones por semana: el 11,1 % de las 852 t/día supuestas para el ASE, frente al mínimo de 13 % que declara su PPSA.
- `PENDIENTE_VERIFICAR`: **un compactador de cargue lateral de 32 yd³ carga 8.000 kg** (`SideLoading.SIDE_LOADER_KG`). Tunjuelo declara 5 de 32 yd³ y ningún operador publica sus kilos. Se toman los 8 t que Torca declara para sus compactadores de 32 yd.
- **Turnos de cargue lateral del ASE 2:** la capa de Tunjuelo asigna cada contenedor al turno de día (609) o al de noche (381), y cada turno es una instancia. Su informe de la Superservicios da los horarios, de 06:00–16:00 y de 18:30–04:30: turnos de 10 h.
- **Turnos de cargue lateral de las demás ASE** (`SideLoading`):
  - **ASE 1:** su capa asigna 155 contenedores al turno de día y 13 al de noche. Las horas son las de sus microrrutas domiciliarias de 3 veces por semana, 06:00–16:00 y 18:00–04:00 (capa de microrrutas del operador). No se cruzó la microrruta de cada contenedor.
  - **ASE 4:** `PENDIENTE_VERIFICAR`. Su capa no trae turno, así que los 763 van al de mañana, 06:00–15:00 (9 h). La Superservicios vio su compactador de carga lateral en la microrruta 411067 a las 10:45, un martes, con frecuencia martes-jueves-sábado.
  - **ASE 3:** `PENDIENTE_VERIFICAR`. Los 91 simulados van al turno de 06:00–14:00 (8 h), el que su informe de la Superservicios pone al lado de la frecuencia de "atención de contenedores"; la tabla del informe no los empareja de forma explícita.
- Supuesto: **los contenedores de las ASE 2 y 4 se recogen 3 veces por semana**, la frecuencia residencial que declaran los dos operadores. Sus capas no traen la frecuencia (en la de Tunjuelo, el campo `FRECUENCIA` está vacío), así que también se mide con 6. El ASE 1 publica 3 para sus 168 contenedores (campo `Frecuencia` de su capa), y el ASE 3 declara 6 para la atención de contenedores.
- **Cuánto cubre el cargue lateral** (`mvn -pl rutea-app exec:java@side-loading`, CSV en `docs/benchmarks/f7-side-loading/`), en toneladas por día y parte de las toneladas de la ASE:

  | ASE | Contenedores | Veces por semana | 50 % | 75 % | 100 % |
  |---|---|---|---:|---:|---:|
  | 1 | 168 reales, 201 m³ | 3 | 6,5 · 0,6 % | 9,7 · 0,8 % | 12,9 · 1,1 % |
  | 2 | 990 reales, 2.178 m³ | 3 (supuesto) | 70,0 · 3,9 % | 105,2 · 5,8 % | 140,0 · 7,8 % |
  | 2 | | 6 (supuesto) | 140,0 · 7,8 % | 210,4 · 11,7 % | 280,0 · 15,5 % |
  | 3 | 91 del plan, 100 m³ | 6 | 6,5 · 0,6 % | 9,7 · 0,9 % | 12,9 · 1,2 % |
  | 4 | 763 reales, 1.831 m³ | 3 (supuesto) | 58,9 · 8,1 % | 88,3 · 12,2 % | 117,7 · 16,2 % |
  | 4 | | 6 (supuesto) | 117,7 · 16,2 % | 176,6 · 24,4 % | 235,4 · 32,5 % |

  En las ASE 1 y 3, el cargue lateral es cerca del 1 % de las toneladas con cualquier llenado. Las toneladas son las de la Superservicios (ver "Toneladas por ASE"); las del ASE 5 son `PENDIENTE_VERIFICAR`.
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
- En el cargue lateral de 2026 (`SideLoading`), hasta 4 viajes por camión. En el ASE 2, con 3 viajes, la flota salía igual con 40 que con 90 min de descarga: en turnos de 10 h con viajes cortos limitaba el máximo, con el turno a medio usar. El escenario de Suba de F3 a F6 sigue con 3, y sus cifras no cambian.

## Calibración del grafo

Desde el centroide del PGIRS (Calle 145 cerca de la Carrera 84B, nodo OSM 5150775121) hasta la portería, el grafo da **29,1 km** de ida y 29,7 km de vuelta, frente a los **32,589 km** del PGIRS: un 10,7 % menos. No se sabe cómo midió el PGIRS (qué entrada, qué ruta, si incluye el recorrido interno hasta el frente de descarga), así que no se ajusta nada para que cuadre.

## Pendiente

- Población de Suba frente a la de Bogotá en la serie oficial DANE–SDP–RMBC (agosto de 2025). Hoy no se usa: se usa el 15 % del PGIRS.
- Ubicación de los contenedores de Suba: la UAESP publica el total (320 aprobados), no dónde están.
- Toneladas del ASE 5 con la misma fuente que las demás.
- Texto oficial de la Resolución CRA 1027 de 2026.
