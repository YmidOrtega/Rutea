import Map from 'react-map-gl/maplibre'
import { DeckOverlay } from './map/DeckOverlay'
import { BASEMAP_STYLE, INITIAL_VIEW_STATE } from './map/view'
import './App.css'

export default function App() {
  return (
    <main className="app">
      <Map initialViewState={INITIAL_VIEW_STATE} mapStyle={BASEMAP_STYLE} attributionControl={{ compact: true }}>
        <DeckOverlay layers={[]} />
      </Map>

      <header className="topbar panel">
        <strong className="brand">Rutea</strong>
        <span className="label">Suba · Bogotá</span>
        <span className="tag">Simulado</span>
      </header>
    </main>
  )
}
