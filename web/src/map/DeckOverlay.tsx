import { MapLibreOverlay, type MapLibreOverlayProps } from '@deck.gl/maplibre'
import { useControl } from 'react-map-gl/maplibre'

/**
 * Capas de deck.gl intercaladas con el mapa de MapLibre. Usa @deck.gl/maplibre, que solo
 * toca la API pública de MapLibre: @deck.gl/mapbox lee campos internos que MapLibre 6 ya no expone.
 */
export function DeckOverlay(props: MapLibreOverlayProps) {
  const overlay = useControl<MapLibreOverlay>(() => new MapLibreOverlay({ ...props, interleaved: true }))
  overlay.setProps(props)
  return null
}
