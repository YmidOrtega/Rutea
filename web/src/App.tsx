import { useCallback, useEffect, useMemo, useState } from 'react'
import MapGL from 'react-map-gl/maplibre'
import type { PickingInfo } from '@deck.gl/core'
import { DeckOverlay } from './map/DeckOverlay'
import { BASEMAP_STYLE, DATA_ATTRIBUTION, initialViewState } from './map/view'
import { readPalette } from './map/colors'
import { operationLayers, staticLayers, tripsLayer, zoneLayers } from './map/layers'
import { clock, secondsOfDay, STREAM_NAMES, toTrips, type Container, type DemoPlan, type Site } from './demo/plan'
import {
  currentPlan,
  isReserveId,
  listenEvents,
  loadPlan,
  overflowPickup,
  requestBreakdown,
  requestOverflow,
  stopAt,
  type CurrentPlan,
  type OperationEvent,
} from './operations/live'
import { ContainerCard } from './panels/ContainerCard'
import { PlanPanel } from './panels/PlanPanel'
import './App.css'

const PLAYBACK_SPEED = 60

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

export default function App() {
  const [plan, setPlan] = useState<DemoPlan | null>(null)
  const [live, setLive] = useState(false)
  const [events, setEvents] = useState<OperationEvent[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  const [eventError, setEventError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [visible, setVisible] = useState<Set<string>>(new Set())
  const [time, setTime] = useState(0)
  const [playing, setPlaying] = useState(() => !prefersReducedMotion())
  const palette = useMemo(() => readPalette(), [])
  const [initialView] = useState(initialViewState)

  // Cada evento llega una vez por WebSocket y, a quien lo pidió, también en la respuesta: se cuenta una sola vez.
  const addEvent = useCallback((event: OperationEvent) => {
    setEvents((current) => (current.some((e) => e.seq === event.seq) ? current : [...current, event]))
    const reserve = event.type === 'breakdown' ? event.reserve : event.routes.filter((route) => isReserveId(route.id))
    setVisible((current) => new Set([...current, ...reserve.map((route) => route.id)]))
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

  const request = (key: string, send: () => Promise<OperationEvent | OperationEvent[]>) => {
    setPending(key)
    setEventError(null)
    send()
      .then((sent) => (Array.isArray(sent) ? sent : [sent]).forEach(addEvent))
      .catch((cause: unknown) => setEventError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => setPending(null))
  }
  const breakdown = (truck: string) => request(truck, () => requestBreakdown(truck, time))
  const overflow = (container: string) => request(container, () => requestOverflow(container, time))

  const current = useMemo(() => (plan ? currentPlan(plan, events) : null), [plan, events])
  // Lo que se reproduce: cada camión averiado se detiene en su avería y las reservas se suman.
  const routes = useMemo(() => {
    if (!current) return []
    const planned = current.plan.routes.map((route) => {
      const at = current.brokenAt.get(route.id)
      return at === undefined ? route : stopAt(route, at)
    })
    return [...planned, ...current.reserve]
  }, [current])
  const orphans = useMemo(
    () => current?.plan.containers.filter((container) => current.orphanRoute.has(container.id)) ?? [],
    [current],
  )
  const overflowed = useMemo(
    () => current?.plan.containers.filter((container) => current.overflows.has(container.id)) ?? [],
    [current],
  )
  const selectedContainer = current?.plan.containers.find((container) => container.id === selected) ?? null

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
      ...(current ? staticLayers(current.plan, visible, palette) : []),
      ...operationLayers(
        current?.reserve.filter((route) => visible.has(route.id)) ?? [],
        orphans,
        overflowed,
        selectedContainer,
        palette,
      ),
    ],
    [zone, current, visible, palette, orphans, overflowed, selectedContainer],
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
        <DeckOverlay
          layers={layers}
          getTooltip={tooltipFor(current, live)}
          onClick={(info) => setSelected(live && CONTAINER_LAYERS.includes(info.layer?.id ?? '') ? (info.object as Container).id : null)}
        />
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

      {current && (
        <PlanPanel
          plan={current.plan}
          visible={visible}
          onToggle={toggleRoute}
          live={live}
          events={events}
          pending={pending}
          eventError={eventError}
          onBreakdown={breakdown}
          clock={(seconds) => clock(secondsOfDay(current.plan.meta.shiftStart) + seconds)}
        />
      )}

      {current && selectedContainer && (
        <ContainerCard
          container={selectedContainer}
          overflow={current.overflows.get(selectedContainer.id)}
          now={time}
          pending={pending !== null}
          error={pending === null && eventError !== null ? eventError : null}
          onReport={() => overflow(selectedContainer.id)}
          onClose={() => setSelected(null)}
          clock={(seconds) => clock(secondsOfDay(current.plan.meta.shiftStart) + seconds)}
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

const CONTAINER_LAYERS = ['containers', 'orphans', 'overflows']

function tooltipFor(current: CurrentPlan | null, live: boolean) {
  return (info: PickingInfo) => (current ? tooltip(info, current, live) : null)
}

function tooltip({ object, layer }: PickingInfo, current: CurrentPlan, live: boolean) {
  if (!object || ![...CONTAINER_LAYERS, 'sites'].includes(layer?.id ?? '')) return null
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
    html: layer?.id === 'sites' ? siteHtml(object as Site) : containerHtml(object as Container, current, live),
  }
}

function containerHtml(container: Container, current: CurrentPlan, live: boolean): string {
  const at = (seconds: number) => clock(secondsOfDay(current.plan.meta.shiftStart) + seconds)
  const reserve = current.orphanRoute.get(container.id)
  const orphan = reserve ? `<span class="orphan">Huérfano tras la avería de ${container.route}: lo recoge ${reserve}</span>` : ''
  const overflow = current.overflows.get(container.id)
  const spilled = overflow
    ? `<span class="orphan num">Desbordado a las ${at(overflow.atSeconds)}: ${overflowPickup(overflow, at)}</span>`
    : ''
  const rules = container.rules.map((rule) => `<span class="rule">${rule}</span>`).join('')
  const hint = live && container.stream === 'NON_RECYCLABLE' && !overflow ? '<span>Clic para reportar un desborde</span>' : ''
  return `<strong class="num">${container.id}</strong>
    <span>${STREAM_NAMES[container.stream]} · simulado</span>
    <span class="num">${container.kg} kg por recolección</span>
    <span class="num">Vehículo ${container.route} · llega a las ${at(container.visitSeconds)}</span>
    ${orphan}${spilled}${rules}${hint}`
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
