import type { MapViewState } from '@deck.gl/core'

/** Estilo base: CARTO Dark Matter sin etiquetas (ver docs/diseno.md). */
export const BASEMAP_STYLE =
  'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json'

/** Vista inicial sobre la localidad de Suba, Bogotá. */
export const INITIAL_VIEW_STATE: MapViewState = {
  longitude: -74.083,
  latitude: 4.741,
  zoom: 13,
  pitch: 45,
  bearing: -15,
}
