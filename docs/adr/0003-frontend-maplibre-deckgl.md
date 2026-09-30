# ADR 0003: Frontend con React, MapLibre y deck.gl

- Estado: aceptada
- Fecha: 2026-09-30

## Contexto

La parte visual es un objetivo del proyecto. Se necesita un mapa vectorial fluido, capas con miles de puntos y camiones animados sobre las rutas.

## Decisión

React + TypeScript con Vite. MapLibre GL para el mapa base (código abierto, sin token) y deck.gl para las capas de datos (`PathLayer`, `TripsLayer`, `ScatterplotLayer`).

## Consecuencias

- Animación de rutas con `TripsLayer` sin escribir shaders.
- Sin dependencia de Mapbox ni de claves de API; el estilo base se puede cambiar por teselas propias.
- El frontend es un proyecto aparte (`web/`), con su propio build y su propia CI.
