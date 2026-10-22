import { WebMercatorViewport, type MapViewState } from '@deck.gl/core'

/**
 * Las rutas también derivan de OpenStreetMap: el crédito "© OpenStreetMap contributors" ya lo pone el mapa
 * base, así que basta con nombrar la licencia (ODbL). Corta para que quepa en una línea en el móvil, y fija
 * porque MapLibre crea el control de atribución una sola vez, antes de que cargue el plan.
 */
export const DATA_ATTRIBUTION = 'Rutas: ODbL'

export const BASEMAP_STYLE =
  'https://basemaps.cartocdn.com/gl/dark-matter-nolabels-gl-style/style.json'

/** Límites de Suba (suba.json), redondeados hacia afuera. */
const SUBA_BOUNDS: [[number, number], [number, number]] = [
  [-74.133, 4.684],
  [-74.034, 4.838],
]

/**
 * Suba entera en el espacio que dejan libre los paneles: a la derecha de la consola y sobre la línea de tiempo en
 * escritorio, entre los dos en el móvil. La base de Fontibón y el relleno quedan fuera del cuadro: el turno se lee
 * en los barrios, no en la autopista.
 */
export function initialViewState(): MapViewState {
  const narrow = window.matchMedia('(max-width: 640px)').matches
  const { longitude, latitude, zoom } = new WebMercatorViewport({
    width: window.innerWidth,
    height: window.innerHeight,
  }).fitBounds(SUBA_BOUNDS, {
    padding: narrow ? { top: 170, bottom: 230, left: 16, right: 16 } : { top: 24, bottom: 250, left: 368, right: 40 },
  })
  // El encuadre se calcula de frente; inclinado, el norte se aleja y en escritorio Suba se ve más chica de lo que cabe
  return { longitude, latitude, zoom: zoom + (narrow ? 0 : 0.2), pitch: 45, bearing: -15 }
}
