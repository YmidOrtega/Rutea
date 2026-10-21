import type { MapViewState } from '@deck.gl/core'

export const BASEMAP_STYLE =
  'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json'

/** En pantallas angostas Suba no cabe sin volver ilegibles las rutas: se encuadra el plan. */
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
