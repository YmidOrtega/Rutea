import { useEffect, useMemo, useState } from 'react'
import Map from 'react-map-gl/maplibre'
import type { PickingInfo } from '@deck.gl/core'
import { DeckOverlay } from './map/DeckOverlay'
import { BASEMAP_STYLE, initialViewState } from './map/view'
import { readPalette } from './map/colors'
import { staticLayers, tripsLayer, zoneLayers } from './map/layers'
import { loadDemoPlan, STREAM_NAMES, toTrips, type Container, type DemoPlan } from './demo/plan'
import { PlanPanel } from './panels/PlanPanel'
import './App.css'

const SHIFT_START_SECONDS = 21 * 3600
const PLAYBACK_SPEED = 60

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

export default function App() {
  const [plan, setPlan] = useState<DemoPlan | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [visible, setVisible] = useState<Set<string>>(new Set())
  const [time, setTime] = useState(0)
  const [playing, setPlaying] = useState(() => !prefersReducedMotion())
  const palette = useMemo(() => readPalette(), [])
  const [initialView] = useState(initialViewState)

  useEffect(() => {
    loadDemoPlan()
      .then((loaded) => {
        setPlan(loaded)
        setVisible(new Set(loaded.routes.map((route) => route.id)))
      })
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause)))
  }, [])

  const trips = useMemo(() => (plan ? toTrips(plan.routes) : []), [plan])
  const duration = useMemo(() => Math.max(0, ...trips.map((trip) => trip.timestamps.at(-1) ?? 0)), [trips])

  useEffect(() => {
    if (!playing || duration === 0) return
    let frame = 0
    let last = performance.now()
    const tick = (now: number) => {
      const elapsed = ((now - last) / 1000) * PLAYBACK_SPEED
      last = now
      setTime((current) => (current + elapsed) % duration)
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [playing, duration])

  const zone = useMemo(() => zoneLayers(palette), [palette])
  const baseLayers = useMemo(
    () => [...zone, ...(plan ? staticLayers(plan, visible, palette) : [])],
    [zone, plan, visible, palette],
  )
  // Antes de reproducir por primera vez, el mapa muestra el plan completo sin estelas
  const layers = plan && (playing || time > 0)
    ? [...baseLayers, tripsLayer(trips, visible, time, palette)]
    : baseLayers

  const toggleRoute = (routeId: string) =>
    setVisible((current) => {
      const next = new Set(current)
      if (!next.delete(routeId)) next.add(routeId)
      return next
    })

  return (
    <main className="app">
      <Map initialViewState={initialView} mapStyle={BASEMAP_STYLE} attributionControl={{ compact: true }}>
        <DeckOverlay layers={layers} getTooltip={tooltip} />
      </Map>

      <header className="topbar panel">
        <strong className="brand">Rutea</strong>
        <span className="label">{plan?.meta.zone ?? 'Suba'} · Bogotá</span>
        <span className="tag">Simulado</span>
        {plan && (
          <>
            <span className="num clock" aria-label="Hora simulada">{clock(SHIFT_START_SECONDS + time)}</span>
            <button
              className="play"
              onClick={() => setPlaying(!playing)}
              aria-label={playing ? 'Pausar' : 'Reproducir'}
            >
              {playing ? '❚❚' : '▶'}
            </button>
          </>
        )}
      </header>

      {plan && <PlanPanel plan={plan} visible={visible} onToggle={toggleRoute} />}

      {error && (
        <div className="status panel" role="alert">
          <strong>No se pudo cargar el plan de demo.</strong>
          <span className="num">{error}</span>
          <span>
            Genéralo con <code className="num">pnpm demo:data</code>.
          </span>
        </div>
      )}
    </main>
  )
}

function clock(seconds: number): string {
  const day = ((Math.floor(seconds) % 86_400) + 86_400) % 86_400
  const hours = Math.floor(day / 3600)
  const minutes = Math.floor((day % 3600) / 60)
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`
}

function tooltip({ object, layer }: PickingInfo) {
  if (layer?.id !== 'containers' || !object) return null
  const container = object as Container
  // deck.gl aplica estilos en línea a su tooltip; se reemplazan con los tokens
  return {
    className: 'tooltip',
    style: {
      background: 'var(--panel)',
      color: 'var(--fg)',
      border: '1px solid var(--line)',
      borderRadius: '8px',
      padding: '8px 10px',
      backdropFilter: 'blur(12px)',
      fontFamily: 'var(--font-ui)',
      fontSize: 'var(--text-13)',
    },
    html: `<strong class="num">${container.id}</strong>
      <span>${STREAM_NAMES[container.stream]}</span>
      <span class="num">${Math.round(container.fill * 100)} % lleno · ${container.kg} kg</span>
      <span class="num">Ruta ${container.route}</span>`,
  }
}
