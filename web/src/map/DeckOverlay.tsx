import { MapLibreOverlay, type MapLibreOverlayProps } from '@deck.gl/maplibre'
import { useControl } from 'react-map-gl/maplibre'

/**
 * @deck.gl/mapbox lee campos internos que MapLibre 6 ya no expone; @deck.gl/maplibre solo usa su
 * API pública.
 */
export function DeckOverlay(props: MapLibreOverlayProps) {
  const overlay = useControl<MapLibreOverlay>(() => new MapLibreOverlay({ ...props, interleaved: true }))
  overlay.setProps(props)
  return null
}
