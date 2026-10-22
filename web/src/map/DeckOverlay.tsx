import { MapLibreOverlay, type MapLibreOverlayProps } from '@deck.gl/maplibre'
import { useControl } from 'react-map-gl/maplibre'

/**
 * @deck.gl/mapbox lee campos internos que MapLibre 6 ya no expone; @deck.gl/maplibre solo usa su
 * API pública. En modo estricto, React quita y vuelve a poner el overlay antes de que deck.gl termine de crear su
 * dispositivo sobre el contexto WebGL del mapa: sin reusarlo, el segundo intento falla y el mapa queda sin capas.
 */
export function DeckOverlay(props: MapLibreOverlayProps) {
  const overlay = useControl<MapLibreOverlay>(
    () => new MapLibreOverlay({ ...props, interleaved: true, deviceProps: { _reuseDevices: true } }),
  )
  overlay.setProps(props)
  return null
}
