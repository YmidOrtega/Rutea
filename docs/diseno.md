# Diseño visual

Rutea se ve como un **instrumento de despacho nocturno**, no como un dashboard con un mapa detrás. La recolección de no aprovechables en Bogotá ocurre sobre todo de noche, así que el diseño parte del tablero de un despachador de noche: interfaz y ciudad en un mismo grafito, y el sodio como única luz cálida. Lo único que rompe ese monocromo son los colores oficiales de las corrientes, los de los operadores y las alertas.

La referencia es el tablero de un hiperauto: una pieza hecha para esta máquina, donde la forma es la función. Los paneles son instrumentos opacos de canto recto, con líneas finas y marcas de registro en las esquinas; no hay vidrio difuminado, píldoras ni esquinas redondeadas.

## La firma: la ciudad se limpia

Lo que hace a Rutea reconocible sin leer una cifra es que la ciudad se limpia detrás de los camiones. Las calles del puerta a puerta empiezan casi apagadas. Cada una se enciende en sodio cuando un camión la recoge, porque eso es lo que está pasando ahora, y en 40 minutos del turno se enfría a `--clean`, un blanco frío: ya está limpia. Un halo aditivo hace que los barrios densos brillen más que las calles sueltas. Al final del turno, el mapa muestra por barrios lo que la flota hizo esa noche. Los contenedores siguen la misma regla con su borde. El color cambia al minuto del reloj y no hay animación aparte, así que con `prefers-reduced-motion` la firma se ve igual.

## Principios

1. **El mapa es la interfaz.** Los paneles son estrechos y se pueden colapsar. Nada tapa más del 30 % del mapa en escritorio.
2. **Una lectura principal.** El instrumento de arriba dice la hora del turno y cuánto va recogido; es lo primero que se lee. El resto del plan es una ficha técnica, en segundo plano.
3. **El color significa algo.** El acento se usa solo para lo que está pasando ahora (hora actual, camión seleccionado, alerta). Las corrientes de residuo usan el código de colores oficial.
4. **Las cifras se leen de un vistazo.** Números en mono con `tabular-nums`, unidades en texto secundario, siempre con su unidad real (km, t, kg CO₂, m³), y escritas como en Colombia: punto de miles y coma decimal (`1.808,7 km`), con `web/src/format.ts`.
5. **Movimiento con propósito.** La animación de los camiones cuenta el plan. No hay animaciones decorativas, y con `prefers-reduced-motion` el mapa arranca en pausa.
6. **Honestidad en los datos.** Todo dato simulado lleva la etiqueta "simulado". Las calles son reales (OpenStreetMap); los contenedores y sus cargas son simulados, y la UI lo dice. Lo que no tiene dato, como el estado de llenado antes de los sensores (F5), no se muestra con un valor inventado.

## Tokens

Definidos en `web/src/styles/tokens.css`. Ningún componente usa colores literales.

| Token | Valor | Uso |
|---|---|---|
| `--bg` | `#0b0c0e` | Fondo de la interfaz: el mismo grafito del mapa |
| `--map-bg` / `--map-street` | `#0b0c0e` / `#8892a4` | El mapa base, de su fondo a sus calles más claras (las avenidas llegan al 40 %): grafito neutro |
| `--panel` | `rgb(11 12 14 / 0.94)` | Instrumentos y paneles, casi opacos y sin difuminado |
| `--line` / `--line-strong` | `rgb(136 146 164 / 0.16)` / `0.34` | Líneas finas, divisores, marcas de escala y de registro, en el tono de las calles del mapa |
| `--fg` | `#e8eaed` | Texto principal |
| `--fg-muted` | `#8e95a1` | Texto secundario, unidades |
| `--sodium` | `#f5a524` | Acento: hora actual, selección, foco y la calle recién recogida. La única luz cálida |
| `--clean` | `#d6e4f2` | La calle ya recogida: blanco frío, lo opuesto al sodio |
| `--ok` / `--warn` / `--crit` | `#93cf9f` / `#e8d47a` / `#ff5b48` | Estados. `--warn` es más claro y amarillo que el sodio para no confundir una advertencia con lo que pasa ahora |

### Operadores (modo Ciudad)

Cada ASE tiene el color de su operador, que se usa en sus rutas, su muestra y sus camiones del panel. Son tonos fríos y rosa, separados unos 35° de matiz. Ninguno es rojo (alerta), ámbar (selección y hora), amarillo (advertencia), verde (orgánicos) ni blanco (aprovechables), así que una ruta nunca se lee como un estado. En el modo en vivo las rutas siguen con el color de su corriente.

| Token | Valor | ASE |
|---|---|---|
| `--operator-1` | `#a78bfa` violeta | 1 · Arzobispo |
| `--operator-2` | `#4fd1e0` cian | 2 · Tunjuelo |
| `--operator-3` | `#f472b6` rosa | 3 · Fucha |
| `--operator-4` | `#6b9cff` azul | 4 · Salitre |
| `--operator-5` | `#e879f9` orquídea | 5 · Torca |

### Corrientes de residuo (Resolución 2184 de 2019)

| Corriente | Color oficial | Cómo se dibuja sobre el mapa oscuro |
|---|---|---|
| Aprovechables | Blanco | Trazo `#f1f2f4` |
| Orgánicos aprovechables | Verde | Trazo `#5fcb8b` |
| No aprovechables | Negro | Núcleo `#0a0a0a` con borde claro `#c3c8d0`, para que el negro oficial se lea sobre el mapa |

## Tipografía

- **Schibsted Grotesk** (variable) para la interfaz y los títulos. Tiene carácter de periódico y señalética, sin ser la grotesca de siempre.
- **Martian Mono** (variable en peso y ancho) para cifras, horas, IDs de ruta y coordenadas. Angosta (87,5 %) en tablas y ancha (112,5 %) en la hora del instrumento, como la cifra de un tablero.
- Escala: 11 / 12 / 13 / 15 / 18 / 24 / 40 px. Etiquetas en mayúsculas con `letter-spacing: 0.12em`.
- Se sirven desde el propio bundle (`@fontsource`), sin CDN.

## Mapa

- Motor: MapLibre GL. Capas de datos: deck.gl.
- Estilo base: CARTO Dark Matter sin etiquetas (`dark-matter-nolabels-gl-style`), con atribución visible a © OpenStreetMap y © CARTO, en un recuadro de línea fina como el resto del tablero y no en la píldora blanca de MapLibre. Al cargar, `web/src/map/basemap.ts` vuelve a pintar cada color del estilo por su luminancia, de `--map-bg` hacia `--map-street`, sin pasar del 40 %. Queda un solo grafito neutro, donde las avenidas son las más claras. Así nada de la ciudad de fondo compite con las rutas, los colores de los operadores o el sodio de lo que pasa ahora (2026-10-29; antes era un monocromo de sodio, y con las cinco ASE encima el mapa se veía marrón). El agua queda más oscura. Si el estilo no se puede leer, se usa sin repintar. Fuera de Suba, una máscara de `--map-bg` al 67 % deja la localidad en primer plano sin borrar la ciudad. Para producción, evaluar teselas propias (Protomaps / PMTiles) con la misma zona.
- Capas:
  - `PathLayer`: rutas completas, en el color de su corriente mezclado al 22 % con `--bg` y opaco: el plan es el fondo y la estela es lo que brilla. Opaco porque una docena de recorridos comparten cada avenida y, con transparencia, se suman hasta verse blancos.
  - `TripsLayer`: el recorrido ya hecho por cada camión, con estela de 2 px en el color de su corriente al 55 % sobre el fondo, para que la calle limpia brille más que el camino al relleno.
  - `ScatterplotLayer`: contenedores, pequeños porque son 1.612. Relleno por corriente; los que faltan por recoger son apenas un punto tenue sin borde y se encienden con su primera visita: su borde pasa de `--sodium` a `--clean`, como las calles. El borde por estado de llenado llega con los sensores (F5); hasta entonces el borde es el de la corriente.
  - `PathLayer`: trozos de calle del puerta a puerta (en vivo), la firma. Pendientes, en `--clean` al 14 % sobre el fondo. Con su primera visita se encienden en `--sodium` y en 40 minutos pasan a `--clean`, con un halo aditivo debajo. El ancho va en metros (7 m, de 0,75 a 4 px), para que a escala de localidad la trama se vea fina y al acercar parezca una calle. El tooltip dice su carga (estimada por población), sus metros, el vehículo y su hora de llegada.
  - `ScatterplotLayer`: sitios. El relleno (punto de descarga) va como punto lleno y la base de operaciones como anillo.
  - Las capas de rutas y viajes no usan prueba de profundidad: muchas comparten el corredor al relleno y, con el mapa inclinado, pelearían pixel a pixel (z-fighting).
- Vista inicial: Suba entera en el espacio que dejan libre los paneles (`web/src/map/view.ts`), calculada para el tamaño de la ventana. La base de Fontibón y el relleno quedan fuera del cuadro: el turno se lee en los barrios. Inclinación 45°, rumbo −15°. Al elegir otra localidad en vivo, o al volver de la ciudad, el mapa se encuadra en su borde con los mismos márgenes y la máscara pasa a esa localidad.
- Los eventos se apagan cuando dejan de pedir algo: un huérfano deja de llevar borde `--crit` cuando la reserva lo recoge, el anillo de un desborde desaparece cuando alguien pasa por él (el tooltip lo sigue contando) y una ruta de reserva va en `--sodium` mientras corre y queda como rastro fino `--fg-muted` al terminar. Al repetir el turno desde antes, lo que todavía no pasó no se dibuja.

## Componentes

- **Instrumento**: arriba a la izquierda. El nombre, la localidad y el sello "Simulado" (un recuadro de línea fina, no una píldora), cuyo título dice qué es real y qué simulado según el plan; la hora del turno grande, en el acento; una escala grabada con una marca por cada décimo y las toneladas recogidas encima, porque con el puerta a puerta los contenedores son la menor parte de la carga; y debajo, dos cifras por fila: toneladas, contenedores (si la franja tiene), km de calle recogidos (si tiene puerta a puerta) y vehículos en ruta. Mientras el servidor arma una operación, la lectura grande cuenta los segundos y las cifras quedan en "--". En pantallas angostas la hora sube a la derecha del nombre.
- **Ficha del plan**: debajo del instrumento, una ficha técnica (recorrido, horas de vehículo, CO₂, vehículos y viajes, contenedores) con la etiqueta a la izquierda, línea de puntos y la cifra a la derecha. Sigue la lista de rutas (activar/desactivar, color de corriente, carga usada / capacidad). En pantallas angostas baja desde la barra superior, colapsado, porque abajo va la línea de tiempo.
- **Línea de tiempo inferior**: el turno entero de un vistazo. Una franja por corriente con las visitas cada 15 min, en el color de la corriente (los no aprovechables con su borde claro, como en el mapa): lo ya recogido va lleno y lo que falta, atenuado, así que el turno se va llenando. La franja de 21:00 a 06:00 se marca con R02 en `--warn`. Las averías y los cierres cruzan la línea con un trazo punteado `--crit` y un rombo arriba, para no confundirse con la aguja de la hora; los desbordes, que con sensores pueden ser decenas, son marcas cortas `--warn` debajo. La hora actual es una línea `--sodium`. La franja R02 va rayada, porque es una condición de la norma y no un dato. Debajo, una marca grabada por hora y una más larga cada dos, con la hora; la hora actual es una aguja con su remate. Arriba, reproducir/pausar (dibujados, no caracteres), la velocidad y qué corriente es cada franja. El avance va en el instrumento: contenedores con al menos una visita y toneladas recogidas, contados desde los recorridos con la misma regla que usa el servidor para sus paradas. Las toneladas suman cada visita, como las rutas: tras un desvío por desborde el camión del plan igual pasa. Sin API, la línea es un deslizador (puntero, flechas de a 5 min, Inicio y Fin); con API, el reloj es de todos y no se arrastra. Va abajo a la derecha del panel, sin tapar la atribución, y la tarjeta del contenedor se apila encima.
- **Eventos (F5)**: con la API detrás, cada compactador de la lista tiene un botón con el triángulo de emergencia de los vehículos que lo avería a la hora del reloj. Con puntero, el triángulo aparece solo en la fila que se mira o tiene el foco: quince triángulos a la vez dirían que todo está mal. En pantallas táctiles queda siempre visible. El del compactador averiado se queda en `--crit`, porque ya no es una acción sino un estado. El camión se detiene donde estaba. Sus contenedores huérfanos llevan borde `--crit` hasta que la reserva los recoge, y la ruta de reserva va en el acento `--sodium` mientras corre, porque es lo que está pasando ahora. El panel registra cada evento con la hora, los contenedores sin recoger, la salida y llegada de la reserva, R05 y la latencia de replanificar. Un clic en un contenedor lo selecciona con un anillo `--sodium` y abre una tarjeta abajo a la derecha (Esc la cierra). Desde ahí se reporta desbordado a la hora del reloj. El contenedor desbordado lleva un anillo `--crit` más amplio que el de los huérfanos hasta que lo recogen, y la tarjeta, el tooltip y el panel dicen quién lo recoge y a qué hora. El turno del compactador que se desvía se redibuja con su desvío. "Cerrar una vía", en el panel, cambia el puntero a una cruz: el siguiente clic cierra la calle más cercana a la hora del reloj (Esc cancela). La calle cerrada es una barrera `--crit` sobre un borde del fondo, los contenedores que quedan sin acceso llevan el borde `--crit` de los huérfanos y los turnos que cambian se redibujan. El panel dice cuánto se desvía cada camión, si termina fuera de turno, cuántas filas y columnas de la matriz se recalcularon y en cuánto tiempo.
- **Reloj y posiciones (F5)**: con la API, el reloj es el del servidor y vale para todos: en la línea de tiempo, ▶/❚❚ lo reproduce o pausa, el botón de velocidad alterna 15×, 60× y 240×, y al terminar el turno ↺ empieza uno nuevo y borra los eventos. Cada vehículo es un punto del color de su estela en la posición que transmite; el averiado lleva borde `--crit` y el que terminó su turno desaparece. Su tooltip dice qué está haciendo.
- **Sensores de llenado (F5)**: un punto en el centro del contenedor, `--warn` desde el 70 % y `--crit` desbordado; los que están bien no llevan punto, porque 806 puntos verdes no dicen nada que la ausencia no diga. El borde sigue siendo el de R07 y el color, el de la corriente. El tooltip y la tarjeta dicen el porcentaje, los litros y que el sensor es simulado. Un desborde que detecta un sensor aparece en el panel como "lleno según su sensor".
- **Horarios de paso (R04)**: una sección plegable del panel, al final, con cada UPZ y la ventana de cada corriente (su muestra de color y las horas). Elegir una UPZ marca su borde en `--sodium` sobre el mapa, porque es una selección; la zona rural no tiene borde y no se elige. "Descargar por vehículo (CSV)" baja una fila por pasada de vehículo. La tarjeta del contenedor dice su UPZ y su horario publicado, y si hoy llega antes o después, cuántos minutos, en `--crit`: el horario publicado sale del plan y no cambia con los eventos.
- **Localidad y franja en vivo (F8)**: el botón de modo dice la localidad elegida ("En vivo · Usaquén"). Debajo, un selector grabado de cuatro posiciones elige la franja (LMV día, LMV noche, MJS día, MJS noche) con el mismo filo `--sodium`. En el mapa, las demás localidades urbanas marcan su borde en `--fg` al pasar el puntero, y su tooltip invita a verlas en vivo; un clic cambia de operación. La elección queda en la dirección (`#vivo=11-LMVNoche`). Sin la API no hay selector de franja ni localidades, porque el plan estático es solo Suba. Si la API rechaza la elección (una localidad rural, datos que faltan), el aviso dice lo que respondió.
- **Modo y ciudad (F7)**: arriba de todo, un selector grabado de dos posiciones: "En vivo · {localidad}" (todo lo anterior) y "Ciudad". La posición elegida lleva un filo `--sodium` abajo. Sin la API, "Ciudad" queda deshabilitado y su título dice cómo arrancarla, porque el plan estático es solo Suba. En la ciudad, el mapa mismo es el selector:
  - Cada localidad es un polígono con su borde fino. Las elegidas se levantan sobre el grafito (relleno `--fg` al 5 %, borde `--sodium` al 60 %) y la que está bajo el puntero, o con el foco en la lista, marca su borde en `--fg`. Un clic la elige o la suelta. El tooltip dice su ASE y su operador.
  - El panel lista las localidades agrupadas por ASE, porque es la ASE la que planifica (ADR 0012). Cada una tiene una casilla grabada que se llena de sodio al elegirla, como en el mapa. Arriba, "Urbanas" elige las 19 urbanas y "Ninguna" limpia.
  - "Planificar" es el único botón con el acento. Mientras el servidor planifica, el pie cuenta los segundos en lugar de un porcentaje inventado, porque el servidor no informa avance. Al terminar, el pie dice cuánto tardó.
  - Cada ASE lleva la muestra del color de su operador. Debajo va su flota: una celda por camión del plan, en ese color, en `--warn` las que pasan de la flota declarada y vacías las declaradas que no se usan. Así se lee de un vistazo si al operador le alcanza. Siguen los kg/día de sus contenedores, si son del operador o simulados, qué parte de sus toneladas cubren ("sin dato" si el operador no las publica) y una fila por turno con su horario, sus contenedores y las paradas R02 y R07.
  - El instrumento cambia de lectura: la cifra grande son los camiones de cargue lateral, en `--fg` y no en el acento, porque no es algo que pase ahora. Su sello dice "Real y simulado".
  - El plan se dibuja entero y sin línea de tiempo: cada ruta en el color de su operador, al 70 % sobre el fondo, porque no hay estela que brille encima y todas son de no aprovechables, y cada contenedor encendido. Con R07 llevan el borde de advertencia de siempre.
  - Cambiar la selección esconde el plan anterior hasta volver a planificar. La selección queda en la dirección (`#ciudad=11,10`), así que se puede compartir y volver a ella. El mapa se encuadra en el Distrito urbano (Sumapaz, rural, lo alejaría todo al sur) y, con un plan, en lo elegido.
- **Tooltip**: al pasar sobre un contenedor muestra ID, corriente, si es simulado o del operador, carga en kg y viaje asignado; sobre un trozo de calle, su carga estimada y su llegada; sobre un sitio, su nombre.

## Accesibilidad

- Contraste AA en todo texto sobre `--panel`.
- Todo control es alcanzable con teclado y tiene foco visible (contorno `--sodium`).
- El estado de llenado no depende solo del color: el tooltip lo dice en porcentaje.
