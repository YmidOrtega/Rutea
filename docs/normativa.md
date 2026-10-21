# Normativa aplicada

Este documento lista cada regla regulatoria que Rutea modela, de dónde sale y cómo se traduce a restricciones del planificador. Es la contraparte de `NormRef` en el código (`rutea-domain`).

**Estado de verificación**

- `VERIFICADA`: contrastada con el texto compilado oficial del decreto o resolución.
- `PENDIENTE_VERIFICAR`: tomada de una fuente secundaria (prensa, resumen, concepto). No se presenta como hecho hasta verificarla.

> Rutea es un proyecto de portafolio. Este documento no es asesoría jurídica. Antes de usar el modelo para operar un servicio real, un profesional debe revisar la normativa vigente y el reglamento técnico aplicable.

Última revisión: 19 de octubre de 2026.

## Marco general

| Norma | Qué regula | Uso en Rutea |
|---|---|---|
| Ley 142 de 1994 | Régimen de servicios públicos domiciliarios, incluido el aseo | Contexto |
| Decreto 1077 de 2015, Libro 2, Parte 3, Título 2 | Servicio público de aseo. Compila el Decreto 2981 de 2013 | Restricciones de rutas, horarios, frecuencias y vehículos |
| Decreto 596 de 2016 | Actividad de aprovechamiento y régimen transitorio de los recicladores de oficio | Rutas selectivas de aprovechables hacia ECA |
| Resolución 2184 de 2019 (MinAmbiente y MinVivienda) | Código de colores para la separación en la fuente, obligatorio desde el 1 de enero de 2021 | Corrientes de residuo y semántica visual |
| Decreto Distrital 345 de 2020 | PGIRS de Bogotá | Contexto de zona y metas |
| Decreto Distrital 653 de 2025, Título 12, modificado por el Decreto 045 de 2026 | Actualización del PGIRS: economía circular y garantía de acciones afirmativas para la población recicladora | Contexto; revisar metas antes de F4 |
| Resolución CRA 1027 de 2026 | Prórroga de los contratos de aseo de Bogotá hasta 2028 (según prensa) | Contexto. `PENDIENTE_VERIFICAR` |

## Reglas modeladas

Artículos del Decreto 1077 de 2015 con su origen en el Decreto 2981 de 2013.

| ID | Regla | Fuente | Restricción en Rutea | Fase | Estado |
|---|---|---|---|---|---|
| R01 | Las macrorrutas y microrrutas consideran, entre otros: tipo de vías y tráfico, uso del suelo, ubicación de hospitales, clínicas y entidades asistenciales, zonas industriales, zonas de difícil acceso, tipo de usuario o generador, áreas públicas, barreras geográficas y tipo de residuo (aprovechable o no). | Art. 2.3.2.2.2.3.30 (D. 2981/2013, art. 31) | Atributos del grafo (tipo de vía) y de los contenedores (uso del suelo, sitios sensibles). Rutas separadas por corriente. | F3–F4 | VERIFICADA |
| R02 | Si la recolección se hace entre las 21:00 y las 06:00 del día siguiente en zonas residenciales, hoteles, hospitales, clínicas y demás centros asistenciales, se toman medidas especiales para mitigar el ruido de la recolección y la compactación. | Art. 2.3.2.2.2.3.31, parágrafo (D. 2981/2013, art. 32) | `CollectionRules.NIGHT_WINDOW` (extremos incluidos) y `CollectionRules.NIGHT_NOISE`. `Compliance` marca cada parada que el camión hace en la franja en calle residencial o frente a un hotel, hospital, clínica o centro asistencial; la UI la muestra en el tooltip y la cuenta en el panel. | F0 (franja) · F4 (paradas) | VERIFICADA |
| R03 | La frecuencia mínima de recolección y transporte de residuos no aprovechables es de dos veces por semana. Para las demás corrientes la frecuencia depende de la generación, los programas de aprovechamiento y el clima. | Art. 2.3.2.2.2.3.32, parágrafo (D. 2981/2013, art. 33) | `CollectionRules.minimumWeeklyFrequency`. `Compliance` valida la frecuencia del plan (6 veces por semana, `SubaFigures`) contra el mínimo. El calendario semanal por zona (VRP periódico) queda para después. | F0 (regla) · F4 (validación) | VERIFICADA |
| R04 | Frecuencias, rutas y horarios se divulgan por medios masivos de amplia circulación local; las facturas informan las frecuencias y la página web del prestador publica rutas y horarios. | Art. 2.3.2.2.2.3.33 (D. 2981/2013, art. 34) | Exportación del plan por sector (horario estimado de paso). | F6 | VERIFICADA |
| R05 | Todo cambio de rutas, horarios o frecuencias se comunica con tres días de anticipación, salvo caso fortuito o fuerza mayor. Ante la avería de un vehículo, el servicio se restablece en máximo tres horas. | Art. 2.3.2.2.2.3.34 (D. 2981/2013, art. 35) | El replanificador distingue cambio planificado de caso fortuito. Una avería dispara replanificación con meta de cobertura ≤ 3 h. | F5 | VERIFICADA |
| R06 | Con más de 5.000 usuarios, los vehículos tienen equipo de compactación, excepto los de residuos separados para aprovechamiento, RCD y otros no compactables. Las cajas compactadoras de vehículos con destino a disposición final son de compactación cerrada, sin pérdida de lixiviado. | Art. 2.3.2.2.2.3.36, num. 3 y 6 (D. 2981/2013, art. 37) | Tipos de vehículo por corriente: compactador para no aprovechables; vehículo sin compactación para aprovechables. | F4 | VERIFICADA |
| R07 | La compactación se hace donde cause la mínima molestia a los residentes y nunca frente a centros educativos, hospitales, clínicas o centros asistenciales. | Art. 2.3.2.2.2.3.27, num. 5 (D. 2981/2013, art. 28) | `CollectionRules.NO_COMPACTION`. `Compliance` marca "sin compactación" cada contenedor a 50 m o menos de un centro educativo, hospital, clínica o centro asistencial de OSM. La UI lo muestra con borde ámbar. El efecto sobre la capacidad (compactar después) aún no se modela. | F4 | VERIFICADA |
| R08 | En ciudades de más de un millón de habitantes, el prestador monitorea la recolección: georreferenciación de microrrutas, posición de los vehículos (GPS) y tiempos y movimientos. | Art. 2.3.2.2.2.3.49 (D. 2981/2013, art. 50) | Justifica la capa de seguimiento en tiempo real (posiciones por WebSocket). | F5 | VERIFICADA |
| R09 | Código de colores para la separación en la fuente: verde para orgánicos aprovechables; blanco para aprovechables (plástico, vidrio, metales, multicapa, papel y cartón); negro para no aprovechables. Obligatorio para municipios y distritos desde el 1 de enero de 2021. | Resolución 2184 de 2019, art. 4 (parágrafo adicionado por la Res. 1344 de 2020, solo para actividades del art. 2.8.10.2 del D. 780 de 2016) | `WasteStream` con su `BinColor` oficial. | F0 | VERIFICADA |
| R10 | El aprovechamiento lo prestan organizaciones de recicladores de oficio; los aprovechables van a una Estación de Clasificación y Aprovechamiento (ECA). | Decreto 596 de 2016 | Las rutas de aprovechables tienen como destino una ECA y pertenecen a un prestador distinto del operador de no aprovechables. | F4 | PENDIENTE_VERIFICAR |

Los artículos R01 a R08 se verificaron el 19 de octubre de 2026 en el [texto compilado del Decreto 1077 de 2015 en Función Pública](https://www.funcionpublica.gov.co/eva/gestornormativo/norma.php?i=77216): ninguno tiene notas de modificación o derogatoria. R09 se verificó en el [gestor normativo de la CRA](https://normas.cra.gov.co/gestor/docs/resolucion_minambienteds_2184_2019.htm), con sus notas de vigencia, y coincide con el normograma de Cancillería.

## Interpretaciones en el código

La norma no dice todo lo que el código necesita decidir. Estas son las decisiones tomadas, para revisar con el operador o la autoridad:

- **"Frente a" (R07, R02):** a 50 m o menos entre el contenedor y el sitio (su nodo en OSM o el vértice más cercano de su predio). `SensitiveSites.FRONT_METERS`, `PENDIENTE_VERIFICAR`.
- **Qué cuenta como cada sitio (etiquetas de OSM):** centro educativo = `amenity=school|kindergarten|college|university`; hospital o clínica = `amenity=hospital|clinic` o `healthcare=hospital|clinic`; centro asistencial = `amenity=nursing_home|social_facility`; hotel = `tourism=hotel`. Los consultorios (`amenity=doctors`) no cuentan.
- **"Zona residencial" (R02):** el contenedor está sobre una calle `residential` o `living_street`. Es una aproximación: no usa el uso del suelo.

## Cómo verificar una regla

1. Abre el texto compilado oficial (Función Pública, Secretaría del Senado o SUIN-Juriscol) y ubica el artículo.
2. Confirma que no fue modificado o derogado (revisa las notas de vigencia).
3. Cambia el estado a `VERIFICADA` aquí y en el `NormRef` correspondiente, y agrega la URL oficial.

## Fuentes consultadas

- [Decreto 1077 de 2015, texto compilado (Función Pública)](https://www.funcionpublica.gov.co/eva/gestornormativo/norma.php?i=77216)
- [Decreto 1077 de 2015, Subsección de recolección (Cancillería, normograma)](https://www.cancilleria.gov.co/sites/default/files/Normograma/docs/pdf/decreto_1077_2015_pr041.pdf)
- [Decreto 1077 de 2015, Título 2. Servicio público de aseo (copia de un prestador)](https://aseosabaneta.com/wp-content/uploads/2021/04/Decreto-1077.pdf)
- [Decreto 596 de 2016 (Función Pública)](https://www.funcionpublica.gov.co/eva/gestornormativo/norma.php?i=69038)
- [Resolución 2184 de 2019, texto compilado (gestor normativo de la CRA)](https://normas.cra.gov.co/gestor/docs/resolucion_minambienteds_2184_2019.htm)
- [Resolución 2184 de 2019, resumen (Safetya)](https://safetya.co/normatividad/resolucion-2184-de-2019/)
- [MinAmbiente: código de colores unificado](https://archivo.minambiente.gov.co/index.php/noticias-minambiente/4595-gobierno-unifica-el-codigo-de-colores-para-la-separacion-de-residuos-en-la-fuente-a-nivel-nacional)
- [Decreto Distrital 345 de 2020 (Alcaldía de Bogotá)](https://www.alcaldiabogota.gov.co/sisjur/normas/Norma1.jsp?i=104052&dt=S)
- [Decreto Distrital 045 de 2026 (Alcaldía de Bogotá)](https://www.alcaldiabogota.gov.co/sisjur//normas/Norma1.jsp?dt=S&i=192145)
- [Bogotá prorrogó los contratos de aseo hasta 2028 (Infobae, feb. 2026)](https://www.infobae.com/colombia/2026/02/09/bogota-amplio-los-contratos-de-aseo-hasta-2028-e-incorporo-nuevos-servicios-asi-quedo-el-modelo-de-recoleccion-de-residuos/)
- [Proyecto de decreto de modificación del Decreto 1077 de 2015 (MinVivienda, 2025)](https://minvivienda.gov.co/system/files/consultasp/proyecto-modificacion-decreto-1077-de-2015-participacion-ciudadana.pdf): revisar si se expidió y qué cambió.
