import type { MapViewState } from '@deck.gl/core'

/**
 * Las rutas también derivan de OpenStreetMap: el crédito "© OpenStreetMap contributors" ya lo pone el mapa
 * base, así que basta con nombrar la licencia (ODbL). Corta para que quepa en una línea en el móvil, y fija
 * porque MapLibre crea el control de atribución una sola vez, antes de que cargue el plan.
 */
export const DATA_ATTRIBUTION = 'Rutas: ODbL'

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
