import type { MapViewState } from '@deck.gl/core'

/** Estilo base: CARTO Dark Matter sin etiquetas (ver docs/diseno.md). */
export const BASEMAP_STYLE =
  'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json'

/**
 * Vista inicial. En escritorio, la localidad de Suba completa, corrida al oriente para que el panel
 * no tape su borde occidental. En pantallas angostas la localidad no cabe sin volver ilegibles las
 * rutas, así que se centra en el plan de demo.
 */
export function initialViewState(): MapViewState {
  const narrow = window.matchMedia('(max-width: 640px)').matches
  return {
    longitude: narrow ? -74.083 : -74.098,
    latitude: narrow ? 4.745 : 4.748,
    zoom: narrow ? 11.8 : 11.9,
    pitch: 45,
    bearing: -15,
  }
}
