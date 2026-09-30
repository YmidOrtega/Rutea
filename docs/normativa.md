# Normativa aplicada

Este documento lista cada regla regulatoria que Rutea modela, de dónde sale y cómo se traduce a restricciones del planificador. Es la contraparte de `NormRef` en el código (`rutea-domain`).

**Estado de verificación**

- `VERIFICADA`: contrastada con el texto compilado oficial del decreto o resolución.
- `PENDIENTE_VERIFICAR`: tomada de una fuente secundaria (prensa, resumen, concepto). No se presenta como hecho hasta verificarla.

> Rutea es un proyecto de portafolio. Este documento no es asesoría jurídica. Antes de usar el modelo para operar un servicio real, un profesional debe revisar la normativa vigente y el reglamento técnico aplicable.

Última revisión: 30 de septiembre de 2026.

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
| R01 | Las macrorrutas y microrrutas deben considerar tipo de vía, usos del suelo, ubicación de hospitales y centros de salud, zonas industriales, zonas de difícil acceso, tipo de usuario, áreas públicas, barreras geográficas y tipo de residuo (aprovechable o no). | Art. 2.3.2.2.2.3.30 (D. 2981/2013, art. 31) | Atributos del grafo (tipo de vía) y de los contenedores (uso del suelo, sensibles). Rutas separadas por corriente. | F3–F4 | PENDIENTE_VERIFICAR |
| R02 | La recolección entre las 21:00 y las 06:00 en zonas residenciales, hoteles y hospitales requiere medidas especiales de mitigación de ruido. | Art. 2.3.2.2.2.3.31 (D. 2981/2013, art. 32) | `CollectionRules.NIGHT_WINDOW`. El plan marca las paradas nocturnas en zonas sensibles. La UI lo muestra. | F4 | PENDIENTE_VERIFICAR |
| R03 | La frecuencia mínima de recolección de residuos no aprovechables es de dos veces por semana. | Art. 2.3.2.2.2.3.32 (D. 2981/2013, art. 33) | Validación del calendario semanal por microrruta. | F4 | PENDIENTE_VERIFICAR |
| R04 | Rutas y horarios deben divulgarse a los usuarios. | Art. 2.3.2.2.2.3.33 (D. 2981/2013, art. 34) | Exportación del plan por sector (horario estimado de paso). | F6 | PENDIENTE_VERIFICAR |
| R05 | Cambios de ruta u horario: aviso con tres días de anticipación, salvo emergencia. Ante una avería, el servicio se restablece en máximo tres horas. | Art. 2.3.2.2.2.3.34 (D. 2981/2013, art. 35) | El replanificador distingue cambio planificado de emergencia. Una avería dispara replanificación con meta de cobertura ≤ 3 h. | F5 | PENDIENTE_VERIFICAR |
| R06 | Municipios con más de 5.000 usuarios usan vehículos con caja compactadora cerrada que evite la pérdida de lixiviados. Se exceptúan la recolección selectiva de aprovechables y la de residuos de construcción y demolición (RCD). | Art. 2.3.2.2.2.3.36 (D. 2981/2013, art. 37) | Tipos de vehículo por corriente: compactador para no aprovechables; vehículo de carga para aprovechables. | F4 | PENDIENTE_VERIFICAR |
| R07 | No se compacta cerca de centros educativos, hospitales y centros de salud. | Decreto 1077 de 2015, sección de recolección (artículo por confirmar) | Paradas marcadas "sin compactación": el vehículo pierde capacidad efectiva en ese tramo. | F4 | PENDIENTE_VERIFICAR |
| R08 | En ciudades de más de 1.000.000 de habitantes, la recolección se monitorea con geolocalización y GPS. | Art. 2.3.2.2.2.3.49 (D. 2981/2013, art. 50) | Justifica la capa de seguimiento en tiempo real (posiciones por WebSocket). | F5 | PENDIENTE_VERIFICAR |
| R09 | Separación en la fuente con tres colores: blanco (aprovechables: plástico, vidrio, metales, multicapa, papel y cartón), verde (orgánicos aprovechables), negro (no aprovechables). | Resolución 2184 de 2019 | `WasteStream` con su color oficial. | F0 | PENDIENTE_VERIFICAR |
| R10 | El aprovechamiento lo prestan organizaciones de recicladores de oficio; los aprovechables van a una Estación de Clasificación y Aprovechamiento (ECA). | Decreto 596 de 2016 | Las rutas de aprovechables tienen como destino una ECA y pertenecen a un prestador distinto del operador de no aprovechables. | F4 | PENDIENTE_VERIFICAR |

## Cómo verificar una regla

1. Abre el texto compilado oficial (Función Pública, Secretaría del Senado o SUIN-Juriscol) y ubica el artículo.
2. Confirma que no fue modificado o derogado (revisa las notas de vigencia).
3. Cambia el estado a `VERIFICADA` aquí y en el `NormRef` correspondiente, y agrega la URL oficial.

## Fuentes consultadas

- [Decreto 1077 de 2015, Subsección de recolección (Cancillería, normograma)](https://www.cancilleria.gov.co/sites/default/files/Normograma/docs/pdf/decreto_1077_2015_pr041.pdf)
- [Decreto 1077 de 2015, Título 2. Servicio público de aseo (copia de un prestador)](https://aseosabaneta.com/wp-content/uploads/2021/04/Decreto-1077.pdf)
- [Decreto 596 de 2016 (Función Pública)](https://www.funcionpublica.gov.co/eva/gestornormativo/norma.php?i=69038)
- [Resolución 2184 de 2019, resumen (Safetya)](https://safetya.co/normatividad/resolucion-2184-de-2019/)
- [MinAmbiente: código de colores unificado](https://archivo.minambiente.gov.co/index.php/noticias-minambiente/4595-gobierno-unifica-el-codigo-de-colores-para-la-separacion-de-residuos-en-la-fuente-a-nivel-nacional)
- [Decreto Distrital 345 de 2020 (Alcaldía de Bogotá)](https://www.alcaldiabogota.gov.co/sisjur/normas/Norma1.jsp?i=104052&dt=S)
- [Decreto Distrital 045 de 2026 (Alcaldía de Bogotá)](https://www.alcaldiabogota.gov.co/sisjur//normas/Norma1.jsp?dt=S&i=192145)
- [Bogotá prorrogó los contratos de aseo hasta 2028 (Infobae, feb. 2026)](https://www.infobae.com/colombia/2026/02/09/bogota-amplio-los-contratos-de-aseo-hasta-2028-e-incorporo-nuevos-servicios-asi-quedo-el-modelo-de-recoleccion-de-residuos/)
- [Proyecto de decreto de modificación del Decreto 1077 de 2015 (MinVivienda, 2025)](https://minvivienda.gov.co/system/files/consultasp/proyecto-modificacion-decreto-1077-de-2015-participacion-ciudadana.pdf): revisar si se expidió y qué cambió.
