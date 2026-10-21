import { useCallback, useEffect, useMemo, useState } from 'react'
import MapGL from 'react-map-gl/maplibre'
import type { PickingInfo } from '@deck.gl/core'
import { DeckOverlay } from './map/DeckOverlay'
import { BASEMAP_STYLE, DATA_ATTRIBUTION, initialViewState } from './map/view'
import { readPalette } from './map/colors'
import { operationLayers, staticLayers, tripsLayer, zoneLayers } from './map/layers'
import { secondsOfDay, STREAM_NAMES, toTrips, type Container, type DemoPlan, type Site } from './demo/plan'
import { eventKey, listenEvents, loadPlan, requestBreakdown, stopAt, type BreakdownEvent } from './operations/live'
import { PlanPanel } from './panels/PlanPanel'
import './App.css'

const PLAYBACK_SPEED = 60

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

export default function App() {
  const [plan, setPlan] = useState<DemoPlan | null>(null)
  const [live, setLive] = useState(false)
  const [events, setEvents] = useState<BreakdownEvent[]>([])
  const [pending, setPending] = useState<string | null>(null)
  const [eventError, setEventError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [visible, setVisible] = useState<Set<string>>(new Set())
  const [time, setTime] = useState(0)
  const [playing, setPlaying] = useState(() => !prefersReducedMotion())
  const palette = useMemo(() => readPalette(), [])
  const [initialView] = useState(initialViewState)

  // Cada evento llega una vez por WebSocket y, a quien lo pidió, también en la respuesta: se cuenta una sola vez.
  const addEvent = useCallback((event: BreakdownEvent) => {
    setEvents((current) => (current.some((e) => eventKey(e) === eventKey(event)) ? current : [...current, event]))
    setVisible((current) => new Set([...current, ...event.reserve.map((route) => route.id)]))
  }, [])

  useEffect(() => {
    loadPlan()
      .then((loaded) => {
        setPlan(loaded.plan)
        setLive(loaded.live)
        setVisible(new Set(loaded.plan.routes.map((route) => route.id)))
        loaded.events.forEach(addEvent)
      })
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause)))
  }, [addEvent])

  useEffect(() => (live ? listenEvents(addEvent) : undefined), [live, addEvent])

  const breakdown = (truck: string) => {
    setPending(truck)
    setEventError(null)
    requestBreakdown(truck, time)
      .then(addEvent)
      .catch((cause: unknown) => setEventError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => setPending(null))
  }

  // El plan vigente: cada camión averiado se detiene en su avería y las reservas se suman.
  const routes = useMemo(() => {
    if (!plan) return []
    const brokenAt = new Map(events.map((event) => [event.truck, event.atSeconds]))
    const planned = plan.routes.map((route) =>
      brokenAt.has(route.id) ? stopAt(route, brokenAt.get(route.id) ?? 0) : route,
    )
    return [...planned, ...events.flatMap((event) => event.reserve)]
  }, [plan, events])
  const reserve = useMemo(() => events.flatMap((event) => event.reserve), [events])
  const orphanRoute = useMemo(() => {
    const byContainer = new Map<string, string>()
    for (const route of reserve) for (const stop of route.stops) byContainer.set(stop, route.id)
    return byContainer
  }, [reserve])
  const orphans = useMemo(
    () => (plan ? plan.containers.filter((container) => orphanRoute.has(container.id)) : []),
    [plan, orphanRoute],
  )

  const trips = useMemo(() => toTrips(routes), [routes])
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
    () => [
      ...zone,
      ...(plan ? staticLayers(plan, visible, palette) : []),
      ...operationLayers(reserve.filter((route) => visible.has(route.id)), orphans, palette),
    ],
    [zone, plan, visible, palette, reserve, orphans],
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
      <MapGL
        initialViewState={initialView}
        mapStyle={BASEMAP_STYLE}
        attributionControl={{ compact: true, customAttribution: DATA_ATTRIBUTION }}
      >
        <DeckOverlay layers={layers} getTooltip={tooltipFor(plan, orphanRoute)} />
      </MapGL>

      <header className="topbar panel">
        <strong className="brand">Rutea</strong>
        <span className="label">{plan?.meta.zone ?? 'Suba'} · Bogotá</span>
        <span className="tag">Simulado</span>
        {plan && (
          <>
            <span className="num clock" aria-label="Hora simulada">{clock(secondsOfDay(plan.meta.shiftStart) + time)}</span>
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

      {plan && (
        <PlanPanel
          plan={plan}
          visible={visible}
          onToggle={toggleRoute}
          live={live}
          events={events}
          pending={pending}
          eventError={eventError}
          onBreakdown={breakdown}
          clock={(seconds) => clock(secondsOfDay(plan.meta.shiftStart) + seconds)}
        />
      )}

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

function tooltipFor(plan: DemoPlan | null, orphanRoute: Map<string, string>) {
  return (info: PickingInfo) => tooltip(info, plan?.meta.shiftStart ?? '00:00', orphanRoute)
}

function tooltip({ object, layer }: PickingInfo, shiftStart: string, orphanRoute: Map<string, string>) {
  if (!object || !['containers', 'orphans', 'sites'].includes(layer?.id ?? '')) return null
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
    html: layer?.id === 'sites' ? siteHtml(object as Site) : containerHtml(object as Container, shiftStart, orphanRoute),
  }
}

function containerHtml(container: Container, shiftStart: string, orphanRoute: Map<string, string>): string {
  const reserve = orphanRoute.get(container.id)
  const orphan = reserve ? `<span class="orphan">Huérfano tras la avería de ${container.route}: lo recoge ${reserve}</span>` : ''
  const rules = container.rules.map((rule) => `<span class="rule">${rule}</span>`).join('')
  return `<strong class="num">${container.id}</strong>
    <span>${STREAM_NAMES[container.stream]} · simulado</span>
    <span class="num">${container.kg} kg por recolección</span>
    <span class="num">Vehículo ${container.route} · llega a las ${clock(secondsOfDay(shiftStart) + container.visitSeconds)}</span>
    ${orphan}${rules}`
}

const SITE_ROLES: Record<Site['id'], string> = {
  relleno: 'Cada viaje de no aprovechables termina aquí a descargar',
  base: 'Los compactadores salen y vuelven aquí',
  eca: 'Supuesta: los aprovechables se clasifican y pesan aquí (R10)',
}

function siteHtml(site: Site): string {
  return `<strong>${site.label}</strong>
    <span>${SITE_ROLES[site.id]}</span>`
}
