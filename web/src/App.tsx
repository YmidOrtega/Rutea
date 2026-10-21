import { useCallback, useEffect, useMemo, useState } from 'react'
import MapGL from 'react-map-gl/maplibre'
import type { PickingInfo } from '@deck.gl/core'
import { DeckOverlay } from './map/DeckOverlay'
import { BASEMAP_STYLE, DATA_ATTRIBUTION, initialViewState } from './map/view'
import { readPalette } from './map/colors'
import { operationLayers, staticLayers, tripsLayer, vehiclesLayer, zoneLayers, type VehicleMark } from './map/layers'
import {
  clock,
  secondsOfDay,
  STREAM_NAMES,
  toTrips,
  type Container,
  type DemoPlan,
  type LonLat,
  type Site,
} from './demo/plan'
import {
  currentPlan,
  isReserveId,
  listen,
  loadPlan,
  overflowPickup,
  requestBreakdown,
  requestClosure,
  requestOverflow,
  roadClassName,
  type ClosureEvent,
  type CurrentPlan,
  type OperationEvent,
} from './operations/live'
import {
  applyTelemetry,
  emptyFeed,
  feedTime,
  feedTrips,
  positionAt,
  setClock,
  VEHICLE_STATES,
  type Feed,
} from './operations/telemetry'
import { ContainerCard } from './panels/ContainerCard'
import { PlanPanel } from './panels/PlanPanel'
import './App.css'

const PLAYBACK_SPEED = 60
/** Velocidades del reloj compartido: un cuarto de hora, una hora o cuatro horas de turno por minuto. */
const SPEEDS = [15, 60, 240]

const prefersReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

export default function App() {
  const [plan, setPlan] = useState<DemoPlan | null>(null)
  const [live, setLive] = useState(false)
  const [events, setEvents] = useState<OperationEvent[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  const [closing, setClosing] = useState(false)
  const [eventError, setEventError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [visible, setVisible] = useState<Set<string>>(new Set())
  const [frameTime, setTime] = useState(0)
  const [reducedMotion] = useState(prefersReducedMotion)
  const [playing, setPlaying] = useState(() => !reducedMotion)
  const [feed, setFeed] = useState<Feed | null>(null)
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
        if (loaded.telemetry) setFeed(applyTelemetry(emptyFeed(loaded.telemetry.clock), loaded.telemetry))
      })
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : String(cause)))
  }, [addEvent])

  useEffect(() => {
    if (!live || !plan) return
    return listen({
      event: addEvent,
      telemetry: (message) => setFeed((current) => applyTelemetry(current ?? emptyFeed(message.clock), message)),
      reset: () => {
        setEvents([])
        setFeed((current) => current && { ...current, vehicles: new Map() })
        setVisible(new Set(plan.routes.map((route) => route.id)))
      },
    })
  }, [live, plan, addEvent])

  const request = (key: string, send: () => Promise<OperationEvent | OperationEvent[]>) => {
    setPending(key)
    setEventError(null)
    send()
      .then((sent) => (Array.isArray(sent) ? sent : [sent]).forEach(addEvent))
      .catch((cause: unknown) => setEventError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => setPending(null))
  }
  const breakdown = (truck: string) => request(truck, () => requestBreakdown(truck))
  const overflow = (container: string) => request(container, () => requestOverflow(container))
  const closure = (at: LonLat) => {
    setClosing(false)
    request('closure', () => requestClosure(at))
  }
  const controlClock = (running: boolean, speed: number) => {
    setEventError(null)
    setClock(running, speed)
      .then((message) => setFeed((current) => applyTelemetry(current ?? emptyFeed(message.clock), message)))
      .catch((cause: unknown) => setEventError(cause instanceof Error ? cause.message : String(cause)))
  }

  useEffect(() => {
    if (!closing) return
    const cancel = (event: KeyboardEvent) => event.key === 'Escape' && setClosing(false)
    window.addEventListener('keydown', cancel)
    return () => window.removeEventListener('keydown', cancel)
  }, [closing])

  const current = useMemo(() => (plan ? currentPlan(plan, events) : null), [plan, events])
  const orphans = useMemo(
    () =>
      current?.plan.containers.filter(
        (container) => current.orphanRoute.has(container.id) || current.isolated.has(container.id),
      ) ?? [],
    [current],
  )
  const overflowed = useMemo(
    () => current?.plan.containers.filter((container) => current.overflows.has(container.id)) ?? [],
    [current],
  )
  const selectedContainer = current?.plan.containers.find((container) => container.id === selected) ?? null

  // Con la API, el mapa dibuja lo que transmiten los vehículos; sin ella, reproduce el plan estático por su cuenta.
  const animated = feed !== null && feed.clock.running && !reducedMotion
  // Pausado, o sin animación por preferencia del sistema, el mapa queda en la hora del último reporte.
  const time = feed && !animated ? feed.clock.seconds : frameTime
  const trips = useMemo(() => (feed ? feedTrips(feed) : toTrips(plan?.routes ?? [])), [feed, plan])
  const duration = useMemo(() => Math.max(0, ...trips.map((trip) => trip.timestamps.at(-1) ?? 0)), [trips])

  useEffect(() => {
    if (!feed || !animated) return
    let frame = 0
    const tick = (now: number) => {
      setTime(feedTime(feed, now))
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [feed, animated])

  useEffect(() => {
    if (feed || !playing || duration === 0) return
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
  }, [feed, playing, duration])

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
        current?.closures ?? [],
        palette,
      ),
    ],
    [zone, current, visible, palette, orphans, overflowed, selectedContainer],
  )
  const marks: VehicleMark[] = feed
    ? [...feed.vehicles.values()].map((vehicle) => ({ vehicle, position: positionAt(vehicle, time) }))
    : []
  // Sin API y antes de reproducir por primera vez, el mapa muestra el plan completo sin estelas
  const layers = feed
    ? [...baseLayers, tripsLayer(trips, visible, time, palette), vehiclesLayer(marks, visible, palette)]
    : plan && (playing || time > 0)
      ? [...baseLayers, tripsLayer(trips, visible, time, palette)]
      : baseLayers
  const sim = feed?.clock
  const finished = sim !== undefined && !sim.running && sim.seconds >= sim.endSeconds

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
          getCursor={({ isHovering }) => (closing ? 'crosshair' : isHovering ? 'pointer' : 'grab')}
          onClick={(info) => {
            if (closing) {
              if (info.coordinate) closure([info.coordinate[0], info.coordinate[1]])
              return
            }
            setSelected(live && CONTAINER_LAYERS.includes(info.layer?.id ?? '') ? (info.object as Container).id : null)
          }}
        />
      </MapGL>

      <header className="topbar panel">
        <strong className="brand">Rutea</strong>
        <span className="label">{plan?.meta.zone ?? 'Suba'} · Bogotá</span>
        <span className="tag">Simulado</span>
        {plan && (
          <>
            <span className="num clock" aria-label="Hora simulada">{clock(secondsOfDay(plan.meta.shiftStart) + time)}</span>
            {sim ? (
              <>
                <button
                  className="play"
                  onClick={() => controlClock(!sim.running, sim.speed)}
                  aria-label={sim.running ? 'Pausar' : finished ? 'Empezar un turno nuevo' : 'Reproducir'}
                  title={finished ? 'Turno nuevo: borra los eventos' : 'El reloj es el mismo para todos'}
                >
                  {sim.running ? '❚❚' : finished ? '↺' : '▶'}
                </button>
                <button
                  className="speed num"
                  onClick={() => controlClock(sim.running, SPEEDS[(SPEEDS.indexOf(sim.speed) + 1) % SPEEDS.length])}
                  aria-label={`Velocidad del reloj: ${sim.speed} veces el tiempo real`}
                >
                  {sim.speed}×
                </button>
              </>
            ) : (
              <button
                className="play"
                onClick={() => setPlaying(!playing)}
                aria-label={playing ? 'Pausar' : 'Reproducir'}
              >
                {playing ? '❚❚' : '▶'}
              </button>
            )}
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
          closing={closing}
          onClosing={setClosing}
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
const CLOSURE_LAYERS = ['closures', 'closure-marks']

function tooltipFor(current: CurrentPlan | null, live: boolean) {
  return (info: PickingInfo) => (current ? tooltip(info, current, live) : null)
}

function tooltip({ object, layer }: PickingInfo, current: CurrentPlan, live: boolean) {
  if (!object || ![...CONTAINER_LAYERS, 'sites', 'vehicles', ...CLOSURE_LAYERS].includes(layer?.id ?? '')) return null
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
    html:
      layer?.id === 'sites'
        ? siteHtml(object as Site)
        : layer?.id === 'vehicles'
          ? vehicleHtml(object as VehicleMark)
          : CLOSURE_LAYERS.includes(layer?.id ?? '')
          ? closureHtml(object as ClosureEvent, current)
          : containerHtml(object as Container, current, live),
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
  const closure = current.isolated.get(container.id)
  const cutOff = closure
    ? `<span class="orphan num">Sin acceso desde las ${at(closure.atSeconds)} por el cierre de una ${roadClassName(closure.roadClass)}</span>`
    : ''
  const rules = container.rules.map((rule) => `<span class="rule">${rule}</span>`).join('')
  const hint = live && container.stream === 'NON_RECYCLABLE' && !overflow ? '<span>Clic para reportar un desborde</span>' : ''
  return `<strong class="num">${container.id}</strong>
    <span>${STREAM_NAMES[container.stream]} · simulado</span>
    <span class="num">${container.kg} kg por recolección</span>
    <span class="num">Vehículo ${container.route} · llega a las ${at(container.visitSeconds)}</span>
    ${orphan}${spilled}${cutOff}${rules}${hint}`
}


function vehicleHtml({ vehicle }: VehicleMark): string {
  return `<strong class="num">${vehicle.id}</strong>
    <span>${capitalize(VEHICLE_STATES[vehicle.state])} · posición transmitida por el servidor</span>`
}

const capitalize = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

function closureHtml(closure: ClosureEvent, current: CurrentPlan): string {
  const at = clock(secondsOfDay(current.plan.meta.shiftStart) + closure.atSeconds)
  const trucks = closure.changes.map((change) => change.truck).join(', ')
  return `<strong class="num">${capitalize(roadClassName(closure.roadClass))} cerrada a las ${at}</strong>
    <span>${trucks ? `Se desvían ${trucks}` : 'No cambia ningún turno'}</span>`
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
