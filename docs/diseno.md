# Diseño visual

Rutea se ve como una **consola de operación nocturna**: el mapa ocupa toda la pantalla y los paneles flotan encima. La recolección de no aprovechables en Bogotá ocurre sobre todo de noche, así que el diseño parte de ahí: un mapa oscuro y un acento del color de las luminarias de sodio de las calles.

## Principios

1. **El mapa es la interfaz.** Los paneles son estrechos, translúcidos y se pueden colapsar. Nada tapa más del 30 % del mapa en escritorio.
2. **El color significa algo.** El acento se usa solo para lo que está pasando ahora (hora actual, camión seleccionado, alerta). Las corrientes de residuo usan el código de colores oficial.
3. **Las cifras se leen de un vistazo.** Números en mono con `tabular-nums`, unidades en texto secundario, siempre con su unidad real (km, t, kg CO₂, m³).
4. **Movimiento con propósito.** La animación de los camiones cuenta el plan. No hay animaciones decorativas, y con `prefers-reduced-motion` el mapa arranca en pausa.
5. **Honestidad en los datos.** Todo dato simulado lleva la etiqueta "simulado". Las calles son reales (OpenStreetMap); los contenedores y sus cargas son simulados, y la UI lo dice. Lo que no tiene dato, como el estado de llenado antes de los sensores (F5), no se muestra con un valor inventado.

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
  - `ScatterplotLayer`: contenedores. Relleno por corriente. El borde por estado de llenado llega con los sensores (F5); hasta entonces el borde es el de la corriente.
  - `ScatterplotLayer`: sitios. El relleno (punto de descarga) va como punto lleno y la base de operaciones como anillo.
  - Las capas de rutas y viajes no usan prueba de profundidad: muchas comparten el corredor al relleno y, con el mapa inclinado, pelearían pixel a pixel (z-fighting).
- Vista inicial: Suba y la base de Fontibón, de donde sale cada turno, a la derecha del panel; en pantallas angostas, solo Suba. Inclinación 45°, rumbo −15°.

## Componentes

- **Barra superior**: nombre, zona, turno y reloj de simulación.
- **Panel izquierdo**: indicadores del plan (km, toneladas, contenedores, CO₂) y la lista de rutas (activar/desactivar, color de corriente, carga usada / capacidad).
- **Línea de tiempo inferior**: reproducir/pausar, velocidad (×60, ×300, ×900) y deslizador de hora. La franja de 21:00 a 06:00 se marca con la nota de la regla R02.
- **Eventos (F5)**: con la API detrás, cada compactador de la lista tiene un botón que lo avería a la hora del reloj. El camión se detiene donde estaba. Sus contenedores huérfanos llevan borde `--crit` y la ruta de reserva va en el acento `--sodium`, porque es lo que está pasando ahora. El panel registra cada evento con la hora, los contenedores sin recoger, la salida y llegada de la reserva, R05 y la latencia de replanificar. Un clic en un contenedor lo selecciona con un anillo `--sodium` y abre una tarjeta abajo a la derecha (Esc la cierra). Desde ahí se reporta desbordado a la hora del reloj. El contenedor desbordado lleva un anillo `--crit` más amplio que el de los huérfanos, y la tarjeta, el tooltip y el panel dicen quién lo recoge y a qué hora. El turno del compactador que se desvía se redibuja con su desvío. "Cerrar una vía", en el panel, cambia el puntero a una cruz: el siguiente clic cierra la calle más cercana a la hora del reloj (Esc cancela). La calle cerrada es una barrera `--crit` sobre un borde del fondo, los contenedores que quedan sin acceso llevan el borde `--crit` de los huérfanos y los turnos que cambian se redibujan. El panel dice cuánto se desvía cada camión, si termina fuera de turno, cuántas filas y columnas de la matriz se recalcularon y en cuánto tiempo.
- **Tooltip**: al pasar sobre un contenedor muestra ID, corriente, carga simulada en kg y viaje asignado; sobre un sitio, su nombre.

## Accesibilidad

- Contraste AA en todo texto sobre `--panel`.
- Todo control es alcanzable con teclado y tiene foco visible (anillo `--sodium`).
- El estado de llenado no depende solo del color: el tooltip lo dice en porcentaje.
