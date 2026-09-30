import { MapboxOverlay, type MapboxOverlayProps } from '@deck.gl/mapbox'
import { useControl } from 'react-map-gl/maplibre'

/** Capas de deck.gl intercaladas con el mapa de MapLibre. */
export function DeckOverlay(props: MapboxOverlayProps) {
  const overlay = useControl<MapboxOverlay>(() => new MapboxOverlay({ ...props, interleaved: true }))
  overlay.setProps(props)
  return null
}
