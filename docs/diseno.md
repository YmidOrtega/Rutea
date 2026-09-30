# Diseño visual

Rutea se ve como una **consola de operación nocturna**: el mapa ocupa toda la pantalla y los paneles flotan encima. La recolección de no aprovechables en Bogotá ocurre sobre todo de noche, así que el diseño parte de ahí: un mapa oscuro y un acento del color de las luminarias de sodio de las calles.

## Principios

1. **El mapa es la interfaz.** Los paneles son estrechos, translúcidos y se pueden colapsar. Nada tapa más del 30 % del mapa en escritorio.
2. **El color significa algo.** El acento se usa solo para lo que está pasando ahora (hora actual, camión seleccionado, alerta). Las corrientes de residuo usan el código de colores oficial.
3. **Las cifras se leen de un vistazo.** Números en mono con `tabular-nums`, unidades en texto secundario, siempre con su unidad real (km, t, kg CO₂, m³).
4. **Movimiento con propósito.** La animación de los camiones cuenta el plan. No hay animaciones decorativas, y con `prefers-reduced-motion` el mapa arranca en pausa.
5. **Honestidad en los datos.** Todo dato simulado lleva la etiqueta "simulado". Hasta F3, la UI dice que las rutas van sobre una cuadrícula y no sobre calles reales.

## Tokens

Definidos en `web/src/styles/tokens.css`. Ningún componente usa colores literales.

| Token | Valor | Uso |
|---|---|---|
| `--bg` | `#0b0f14` | Fondo detrás del mapa |
| `--panel` | `rgb(16 22 30 / 0.82)` | Paneles flotantes (con `backdrop-filter: blur`) |
| `--line` | `rgb(148 163 184 / 0.16)` | Bordes y divisores |
| `--fg` | `#e6edf3` | Texto principal |
| `--fg-muted` | `#8b98a8` | Texto secundario, unidades |
| `--sodium` | `#f5a524` | Acento: hora actual, selección, foco |
| `--ok` / `--warn` / `--crit` | `#4ade80` / `#fbbf24` / `#f87171` | Estado de llenado de contenedores |

### Corrientes de residuo (Resolución 2184 de 2019)

| Corriente | Color oficial | Cómo se dibuja sobre el mapa oscuro |
|---|---|---|
| Aprovechables | Blanco | Trazo `#f1f5f9` |
| Orgánicos aprovechables | Verde | Trazo `#34d399` |
| No aprovechables | Negro | Núcleo `#0a0a0a` con borde claro `#cbd5e1`, para que el negro oficial se lea sobre el mapa |

## Tipografía

- **Schibsted Grotesk** (variable) para la interfaz y los títulos. Tiene carácter de periódico y señalética, sin ser la grotesca de siempre.
- **IBM Plex Mono** para cifras, horas, IDs de ruta y coordenadas.
- Escala: 12 / 13 / 15 / 18 / 24 / 32 px. Etiquetas en mayúsculas con `letter-spacing: 0.08em`.
- Se sirven desde el propio bundle (`@fontsource`), sin CDN.

## Mapa

- Motor: MapLibre GL. Capas de datos: deck.gl.
- Estilo base: CARTO Dark Matter sin etiquetas (`dark-matter-nolabels-gl-style`), con atribución visible a © OpenStreetMap y © CARTO. Para producción, evaluar teselas propias (Protomaps / PMTiles) con la misma zona.
- Capas:
  - `PathLayer`: rutas completas, atenuadas (opacidad 0.35), con el color de su corriente.
  - `TripsLayer`: el recorrido ya hecho por cada camión, con estela.
  - `ScatterplotLayer`: contenedores. Relleno por corriente; borde por estado de llenado.
  - `IconLayer` o `ScatterplotLayer`: base de operaciones, ECA y punto de descarga.
- Vista inicial: Suba, inclinación 45°, rumbo −15°.

## Componentes

- **Barra superior**: nombre, zona, turno y reloj de simulación.
- **Panel izquierdo**: indicadores del plan (km, toneladas, contenedores, CO₂) y la lista de rutas (activar/desactivar, color de corriente, carga usada / capacidad).
- **Línea de tiempo inferior**: reproducir/pausar, velocidad (×60, ×300, ×900) y deslizador de hora. La franja de 21:00 a 06:00 se marca con la nota de la regla R02.
- **Tooltip**: al pasar sobre un contenedor muestra ID, corriente, llenado y ruta asignada.

## Accesibilidad

- Contraste AA en todo texto sobre `--panel`.
- Todo control es alcanzable con teclado y tiene foco visible (anillo `--sodium`).
- El estado de llenado no depende solo del color: el tooltip lo dice en porcentaje.
