import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import MapGL, { type MapRef } from 'react-map-gl/maplibre'
import type { PickingInfo } from '@deck.gl/core'
import { DeckOverlay } from './map/DeckOverlay'
import { DATA_ATTRIBUTION, initialViewState, livePadding } from './map/view'
import { graphiteBasemap } from './map/basemap'
import { readPalette } from './map/colors'
import {
  cityRoutesLayer,
  containersLayer,
  fillLayer,
  localityLayers,
  operationLayers,
  sectorLayer,
  staticLayers,
  streetsLayer,
  SUBA,
  tripsLayer,
  vehiclesLayer,
  zoneLayers,
  type VehicleMark,
} from './map/layers'
import {
  clock,
  secondsOfDay,
  STREAM_NAMES,
  toTrips,
  type Container,
  type DemoPlan,
  type LonLat,
  type Sector,
  type Site,
  type Street,
  type WasteStream,
} from './demo/plan'
import {
  currentPlan,
  fetchEvents,
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
  type ShiftMessage,
} from './operations/live'
import {
  applySensors,
  applyTelemetry,
  emptyFeed,
  emptySensors,
  feedTime,
  fetchSensors,
  fetchTelemetry,
  feedTrips,
  positionAt,
  setClock,
  VEHICLE_STATES,
  fillState,
  type Feed,
  type Sensors,
  type VehicleState,
} from './operations/telemetry'
import { ContainerCard } from './panels/ContainerCard'
import { PlanPanel } from './panels/PlanPanel'
import { Timeline } from './panels/Timeline'
import { Instrument } from './panels/Instrument'
import { collectedBy, firstVisit, progressOf } from './operations/progress'
import { FIRST_KEY, hashOfKey, keyFromHash, sameKey, SLOTS, type LiveKey } from './operations/key'
import { windowAt } from './demo/schedule'
import { CityInstrument, CityPanel } from './city/CityPanel'
import {
  boundsOf,
  fetchLocalities,
  fetchSelection,
  hashOf,
  mergePlans,
  selectionFromHash,
  type CityContainer,
  type Locality,
  type SelectionPlan,
} from './city/city'
import { decimal } from './format'
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
  const [sector, setSector] = useState<string | null>(null)
  const [pending, setPending] = useState<string | null>(null)
  const [closing, setClosing] = useState(false)
  const [eventError, setEventError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [visible, setVisible] = useState<Set<string>>(new Set())
  const [frameTime, setTime] = useState(0)
  const [reducedMotion] = useState(prefersReducedMotion)
  const [playing, setPlaying] = useState(() => !reducedMotion)
  const [feed, setFeed] = useState<Feed | null>(null)
  const [sensors, setSensors] = useState<Sensors>(emptySensors)
  const palette = useMemo(() => readPalette(), [])
  const [initialView] = useState(initialViewState)
  const [basemap, setBasemap] = useState<Awaited<ReturnType<typeof graphiteBasemap>> | null>(null)
  const map = useRef<MapRef>(null)
  // El ref llega después de los efectos del primer render: encuadrar antes de que cargue no hace nada.
  const [mapReady, setMapReady] = useState(false)

  const [liveKey, setLiveKey] = useState<LiveKey>(() => keyFromHash() ?? FIRST_KEY)
  const [loadingSince, setLoadingSince] = useState<number | null>(() => performance.now())

  // La ciudad: elegir localidades y ver cada ASE con su flota. Necesita la API; el plan estático es solo Suba.
  const [mode, setMode] = useState<'live' | 'city'>(() => (selectionFromHash() === null ? 'live' : 'city'))
  const [localities, setLocalities] = useState<Locality[] | null>(null)
  const [chosen, setChosen] = useState<Set<number>>(() => new Set(selectionFromHash() ?? []))
  const [hovered, setHovered] = useState<number | null>(null)
  const [cityResult, setCityResult] = useState<SelectionPlan | null>(null)
  const [planningSince, setPlanningSince] = useState<number | null>(null)
  const [cityError, setCityError] = useState<string | null>(null)
  const planning = useRef<AbortController | null>(null)

  const shift = useRef<number | null>(null)
  const lastSeq = useRef(0)

  const switchLive = (key: LiveKey) => {
    setPlan(null)
    setLive(false)
    setEvents([])
    setFeed(null)
    setSensors(emptySensors())
    setSelected(null)
    setSector(null)
    setClosing(false)
    setEventError(null)
    setError(null)
    setTime(0)
    lastSeq.current = 0
    setLoadingSince(performance.now())
    setLiveKey(key)
  }

  useEffect(() => {
    void fetchLocalities().then(setLocalities)
    // Un enlace pegado en la misma pestaña cambia solo el hash: sin recargar, la selección también cambia.
    const follow = () => {
      const selection = selectionFromHash()
      setMode(selection === null ? 'live' : 'city')
      if (selection !== null) setChosen(new Set(selection))
      const key = keyFromHash()
      if (key) switchLive(key)
    }
    window.addEventListener('hashchange', follow)
    return () => window.removeEventListener('hashchange', follow)
  }, [])

  // Sin la API el plan estático no es de ninguna franja: la dirección no dice una.
  useEffect(() => {
    const hash =
      mode === 'city' ? hashOf([...chosen].sort((a, b) => a - b)) : localities ? hashOfKey(liveKey) : ''
    if (hash !== window.location.hash) {
      window.history.replaceState(null, '', hash || window.location.pathname + window.location.search)
    }
  }, [mode, chosen, liveKey, localities])

  const toggleLocality = (number: number) =>
    setChosen((current) => {
      const next = new Set(current)
      if (!next.delete(number)) next.add(number)
      return next
    })

  const planCity = () => {
    planning.current?.abort()
    const abort = new AbortController()
    planning.current = abort
    setCityError(null)
    setPlanningSince(performance.now())
    fetchSelection([...chosen].sort((a, b) => a - b), abort.signal)
      .then(setCityResult)
      .catch((cause: unknown) => {
        if (!abort.signal.aborted) setCityError(cause instanceof Error ? cause.message : String(cause))
      })
      .finally(() => {
        if (planning.current === abort) setPlanningSince(null)
      })
  }

  const cityPlan = useMemo(() => {
    const current =
      cityResult !== null &&
      cityResult.localities.length === chosen.size &&
      cityResult.localities.every((n) => chosen.has(n))
    return current ? cityResult : null
  }, [cityResult, chosen])
  const cityDrawn = useMemo(() => (cityPlan ? mergePlans(cityPlan) : null), [cityPlan])

  // Al entrar a la ciudad se ve el Distrito urbano (Sumapaz, rural, alejaría todo al sur). Con un plan, sus
  // contenedores: el borde de Usme o de Ciudad Bolívar llega hasta el campo y dejaría el plan en un rincón.
  useEffect(() => {
    if (mode !== 'city' || !localities || !mapReady || !map.current) return
    const framed = localities.filter((locality) =>
      cityPlan ? cityPlan.localities.includes(locality.number) : !locality.rural,
    )
    if (framed.length === 0) return
    const rings =
      cityDrawn && cityDrawn.containers.length > 0
        ? [cityDrawn.containers.map((container) => container.position)]
        : framed.flatMap((locality) => locality.outline)
    const narrow = window.matchMedia('(max-width: 640px)').matches
    map.current.fitBounds(boundsOf(rings), {
      // En el móvil la consola baja hasta el 55 % del alto (App.css): el mapa se encuadra debajo.
      padding: narrow
        ? { top: Math.round(window.innerHeight * 0.55) + 24, bottom: 40, left: 16, right: 16 }
        : { top: 24, bottom: 40, left: 368, right: 40 },
      pitch: 30,
      bearing: -15,
      // Con un solo contenedor el rectángulo es un punto
      maxZoom: 15,
      duration: reducedMotion ? 0 : 1200,
    })
  }, [mode, localities, cityPlan, cityDrawn, mapReady, reducedMotion])

  // La vista inicial ya es Suba; otra localidad, o volver de la ciudad, se encuadra con su borde.
  const framed = useRef<number | null>(FIRST_KEY.locality)
  const enterLive = () => {
    setMode('live')
    framed.current = null
  }
  const liveLocality = localities?.find((locality) => locality.number === liveKey.locality) ?? null
  const zoneOutline = liveLocality?.outline ?? SUBA

  useEffect(() => {
    if (mode !== 'live' || !liveLocality || !mapReady || !map.current) return
    if (framed.current === liveLocality.number) return
    framed.current = liveLocality.number
    map.current.fitBounds(boundsOf(liveLocality.outline), {
      padding: livePadding(),
      pitch: 45,
      bearing: -15,
      duration: reducedMotion ? 0 : 1200,
    })
  }, [mode, liveLocality, mapReady, reducedMotion])

  useEffect(() => {
    void graphiteBasemap(palette).then(setBasemap)
  }, [palette])

  // Cada evento llega una vez por WebSocket y, a quien lo pidió, también en la respuesta: se cuenta una sola vez.
  const addEvent = useCallback((event: OperationEvent) => {
    lastSeq.current = Math.max(lastSeq.current, event.seq)
    setEvents((current) => (current.some((e) => e.seq === event.seq) ? current : [...current, event]))
    const reserve = event.type === 'breakdown' ? event.reserve : event.routes.filter((route) => isReserveId(route.id))
    setVisible((current) => new Set([...current, ...reserve.map((route) => route.id)]))
  }, [])

  const chooseKey = (key: LiveKey) => {
    if (!sameKey(key, liveKey)) switchLive(key)
  }

  useEffect(() => {
    let stale = false
    loadPlan(liveKey)
      .then((loaded) => {
        if (stale) return
        setPlan(loaded.plan)
        setLive(loaded.live)
        setVisible(new Set(loaded.plan.routes.map((route) => route.id)))
        shift.current = loaded.events?.shift ?? null
        loaded.events?.events.forEach(addEvent)
        if (loaded.telemetry) setFeed(applyTelemetry(emptyFeed(loaded.telemetry.clock), loaded.telemetry))
        if (loaded.sensors) setSensors(applySensors(emptySensors(), loaded.sensors))
      })
      .catch((cause: unknown) => !stale && setError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => !stale && setLoadingSince(null))
    return () => {
      stale = true
    }
  }, [liveKey, addEvent])

  useEffect(() => {
    if (!live || !plan) return
    return listen(liveKey, {
      event: addEvent,
      telemetry: (message) => setFeed((current) => applyTelemetry(current ?? emptyFeed(message.clock), message)),
      sensors: (message) => setSensors((current) => applySensors(current, message)),
      shift: (message: ShiftMessage, reconnected: boolean) => {
        const newShift = message.shift !== shift.current
        if (newShift) {
          shift.current = message.shift
          lastSeq.current = 0
          setEvents([])
          setVisible(new Set(plan.routes.map((route) => route.id)))
        }
        // Los reportes de telemetría y sensores traen solo lo que cambió: tras un corte o un turno nuevo, se rehacen.
        const snapshots = newShift || reconnected
        void Promise.all([
          message.seq > lastSeq.current ? fetchEvents(liveKey, lastSeq.current) : null,
          snapshots ? fetchTelemetry(liveKey) : null,
          snapshots ? fetchSensors(liveKey) : null,
        ]).then(([page, telemetry, readings]) => {
          if (page?.shift === shift.current) page.events.forEach(addEvent)
          if (telemetry) setFeed(applyTelemetry(emptyFeed(telemetry.clock), telemetry))
          if (readings) setSensors(applySensors(emptySensors(), readings))
        })
      },
    })
  }, [live, plan, liveKey, addEvent])

  const request = (key: string, send: () => Promise<OperationEvent | OperationEvent[]>) => {
    setPending(key)
    setEventError(null)
    send()
      .then((sent) => (Array.isArray(sent) ? sent : [sent]).forEach(addEvent))
      .catch((cause: unknown) => setEventError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => setPending(null))
  }
  const breakdown = (truck: string) => request(truck, () => requestBreakdown(liveKey, truck))
  const overflow = (container: string) => request(container, () => requestOverflow(liveKey, container))
  const closure = (at: LonLat) => {
    setClosing(false)
    request('closure', () => requestClosure(liveKey, at))
  }
  const controlClock = (running: boolean, speed: number) => {
    setEventError(null)
    setClock(liveKey, running, speed)
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
  const progress = useMemo(() => (current ? progressOf(current, events) : null), [current, events])
  const selectedSector = plan?.sectors.find((s) => s.ref === sector) ?? null
  // El horario publicado sale del plan original: un evento cambia la llegada de hoy, no lo que se publicó (R05).
  const planned = plan?.containers.find((container) => container.id === selected)
  const containerSector = plan?.sectors.find((s) => s.ref === planned?.sector)
  const streams = useMemo(
    () => STREAM_ORDER.filter((stream) => plan?.routes.some((route) => route.stream === stream)),
    [plan],
  )

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

  const firstAt = useMemo(() => {
    const first = new Map<string, number>()
    for (const visit of progress?.visits ?? []) if (!first.has(visit.stop)) first.set(visit.stop, visit.seconds)
    return first
  }, [progress])
  const shown = useMemo(() => current?.plan.containers.filter((c) => visible.has(c.route)) ?? [], [current, visible])
  const shownStreets = useMemo(
    () => current?.plan.streets.filter((street) => visible.has(street.route)) ?? [],
    [current, visible],
  )
  const zone = useMemo(() => {
    const others = localities?.filter((locality) => !locality.rural && locality.number !== liveKey.locality) ?? []
    return [...zoneLayers(zoneOutline, palette), ...localityLayers(others, new Set(), hovered, palette)]
  }, [zoneOutline, localities, liveKey, hovered, palette])
  const planLayers = useMemo(
    () => [...zone, ...(current ? staticLayers(current.plan, visible, palette) : [])],
    [zone, current, visible, palette],
  )
  const minute = Math.floor(time / 60)
  const eventLayers = useMemo(
    () => [
      ...operationLayers(
        current?.reserve.filter((route) => visible.has(route.id)) ?? [],
        orphans,
        overflowed,
        current?.overflows ?? new Map(),
        firstAt,
        selectedContainer,
        current?.closures ?? [],
        minute * 60,
        palette,
      ),
      ...(selectedSector && selectedSector.outline.length > 0 ? [sectorLayer(selectedSector.outline, palette)] : []),
    ],
    [current, visible, palette, orphans, overflowed, firstAt, selectedContainer, minute, selectedSector],
  )
  const baseLayers = [
    ...planLayers,
    streetsLayer(shownStreets, firstAt, time, palette),
    containersLayer(shown, firstAt, time, palette),
    ...eventLayers,
  ]
  const marks: VehicleMark[] = feed
    ? [...feed.vehicles.values()].map((vehicle) => ({ vehicle, position: positionAt(vehicle, time) }))
    : []
  // Sin API y antes de reproducir por primera vez, el mapa muestra el plan completo sin estelas
  const filled = useMemo(
    () => (current ? fillLayer(shown, sensors.readings, palette) : null),
    [current, shown, sensors, palette],
  )
  const cityLayers = useMemo(() => {
    if (!localities) return []
    const selector = localityLayers(localities, chosen, hovered, palette)
    if (!cityDrawn) return selector
    // Sin línea de tiempo: el plan de la ciudad se mira entero, con cada contenedor ya encendido.
    const lit = new Map(cityDrawn.containers.map((container) => [container.id, 0]))
    return [
      ...selector,
      cityRoutesLayer(cityDrawn.routes, palette),
      ...staticLayers({ routes: [], sites: cityDrawn.sites }, new Set(), palette),
      containersLayer(cityDrawn.containers, lit, 0, palette),
    ]
  }, [localities, chosen, hovered, cityDrawn, palette])
  const layers = mode === 'city'
    ? cityLayers
    : feed
    ? [
        ...baseLayers,
        ...(filled ? [filled] : []),
        tripsLayer(trips, visible, time, palette),
        vehiclesLayer(marks, visible, palette),
      ]
    : plan && (playing || time > 0)
      ? [...baseLayers, tripsLayer(trips, visible, time, palette)]
      : baseLayers
  const sim = feed?.clock
  const finished = sim !== undefined && !sim.running && sim.seconds >= sim.endSeconds
  const end = Math.max(plan?.meta.shiftSeconds ?? 0, sim?.endSeconds ?? duration, progress?.lastSeconds ?? 0)
  const at = (seconds: number) => clock(secondsOfDay(plan?.meta.shiftStart ?? '00:00') + seconds)
  const onStreet = feed
    ? [...feed.vehicles.values()].filter((vehicle) => ON_STREET.includes(vehicle.state)).length
    : undefined

  const toggleRoute = (routeId: string) =>
    setVisible((current) => {
      const next = new Set(current)
      if (!next.delete(routeId)) next.add(routeId)
      return next
    })

  return (
    <main className="app">
      {basemap && (
        <MapGL
          ref={map}
          initialViewState={initialView}
          mapStyle={basemap}
          onLoad={() => setMapReady(true)}
          // deck.gl no siempre recibe la salida del puntero hacia la consola: sin esto el borde queda marcado
          onMouseOut={() => setHovered(null)}
          attributionControl={{ compact: true, customAttribution: DATA_ATTRIBUTION }}
        >
          <DeckOverlay
            layers={layers}
            getTooltip={mode === 'city' ? cityTooltip : tooltipFor(current, live, sensors, firstAt)}
            getCursor={({ isHovering }) => (closing && mode === 'live' ? 'crosshair' : isHovering ? 'pointer' : 'grab')}
            onHover={(info) =>
              // Al salir de un polígono, deck.gl avisa con la capa pero sin objeto
              setHovered(
                info.layer?.id === 'localities' && info.object ? (info.object as { locality: Locality }).locality.number : null,
              )
            }
            onClick={(info) => {
              const locality = info.layer?.id === 'localities' && info.object ? (info.object as { locality: Locality }).locality : null
              if (mode === 'city') {
                if (locality) toggleLocality(locality.number)
                return
              }
              if (closing) {
                if (info.coordinate) closure([info.coordinate[0], info.coordinate[1]])
                return
              }
              if (locality) {
                setHovered(null)
                chooseKey({ ...liveKey, locality: locality.number })
                return
              }
              setSelected(live && CONTAINER_LAYERS.includes(info.layer?.id ?? '') ? (info.object as Container).id : null)
            }}
          />
        </MapGL>
      )}

      <div className="console">
        <nav className="modes panel" aria-label="Modo">
          <button aria-pressed={mode === 'live'} onClick={enterLive}>
            En vivo · {liveLocality?.name ?? plan?.meta.zone ?? 'Suba'}
          </button>
          <button
            aria-pressed={mode === 'city'}
            disabled={!localities}
            title={localities ? 'Elegir localidades y planificar por ASE' : 'Necesita la API: mvn -pl rutea-app spring-boot:run'}
            onClick={() => setMode('city')}
          >
            Ciudad
          </button>
        </nav>

        {mode === 'live' && localities && (
          <nav className="modes slots panel" aria-label="Franja del puerta a puerta">
            {SLOTS.map(({ slot, label, title }) => (
              <button
                key={slot}
                aria-pressed={liveKey.slot === slot}
                title={title}
                onClick={() => chooseKey({ ...liveKey, slot })}
              >
                {label}
              </button>
            ))}
          </nav>
        )}

        {mode === 'city' && localities && (
          <>
            <CityInstrument result={cityPlan} selected={chosen.size} planningSince={planningSince} />
            <CityPanel
              localities={localities}
              selected={chosen}
              onToggle={toggleLocality}
              onSelect={(numbers) => setChosen(new Set(numbers))}
              hovered={hovered}
              onHover={setHovered}
              result={cityResult}
              planningSince={planningSince}
              error={cityError}
              onPlan={planCity}
            />
          </>
        )}

        {mode === 'live' && <Instrument
          zone={liveLocality?.name ?? plan?.meta.zone ?? 'Suba'}
          provenance={plan?.meta.network ?? null}
          time={plan ? at(time) : null}
          shift={plan ? `${at(0)}–${at(plan.meta.shiftSeconds)}` : null}
          done={progress ? collectedBy(progress, time) : null}
          totalContainers={current?.plan.containers.length ?? 0}
          totalKg={progress?.kgBefore[progress.kgBefore.length - 1] ?? 0}
          totalMeters={progress?.metersBefore[progress.metersBefore.length - 1] ?? 0}
          onStreet={onStreet}
          planningSince={loadingSince}
        />}

        {mode === 'live' && current && progress && (
          <PlanPanel
            sector={sector}
            onSector={setSector}
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
            clock={at}
          />
        )}
      </div>

      {mode === 'live' && <div className="dock">
        {current && selectedContainer && (
          <ContainerCard
            container={selectedContainer}
            fill={sensors.readings.get(selectedContainer.id)}
            liters={sensors.liters.get(selectedContainer.id)}
            overflow={current.overflows.get(selectedContainer.id)}
            visit={progress ? firstVisit(progress, selectedContainer.id) : undefined}
            sectorName={containerSector && sectorLabel(containerSector)}
            published={containerSector && planned ? windowAt(containerSector, planned.stream, planned.visitSeconds) : undefined}
            now={time}
            pending={pending !== null}
            error={pending === null && eventError !== null ? eventError : null}
            onReport={() => overflow(selectedContainer.id)}
            onClose={() => setSelected(null)}
            clock={at}
          />
        )}

        {current && progress && (
          <Timeline
            shiftStart={current.plan.meta.shiftStart}
            end={end}
            time={time}
            progress={progress}
            streams={streams}
            events={events}
            night={current.plan.compliance.find((check) => check.id === 'R02')}
            clock={at}
            onSeek={
              sim
                ? undefined
                : (seconds) => {
                    setPlaying(false)
                    setTime(seconds)
                  }
            }
          >
            {sim ? (
              <>
                <button
                  className="control play"
                  onClick={() => controlClock(!sim.running, sim.speed)}
                  aria-label={sim.running ? 'Pausar' : finished ? 'Empezar un turno nuevo' : 'Reproducir'}
                  title={finished ? 'Turno nuevo: borra los eventos' : 'El reloj es el mismo para todos'}
                >
                  <PlayIcon state={sim.running ? 'pause' : finished ? 'restart' : 'play'} />
                </button>
                <button
                  className="control num"
                  onClick={() => controlClock(sim.running, SPEEDS[(SPEEDS.indexOf(sim.speed) + 1) % SPEEDS.length])}
                  aria-label={`Velocidad del reloj: ${sim.speed} veces el tiempo real`}
                >
                  {sim.speed}×
                </button>
              </>
            ) : (
              <button className="control play" onClick={() => setPlaying(!playing)} aria-label={playing ? 'Pausar' : 'Reproducir'}>
                <PlayIcon state={playing ? 'pause' : 'play'} />
              </button>
            )}
          </Timeline>
        )}
      </div>}

      {error && mode === 'live' && (
        <div className="status panel" role="alert">
          <strong>No se pudo cargar la operación.</strong>
          <span className="num">{error}</span>
          {!localities && (
            <span>
              Arranca la API (<code className="num">mvn -pl rutea-app spring-boot:run</code>) o genera el plan de demo
              con <code className="num">pnpm demo:data</code>.
            </span>
          )}
        </div>
      )}
    </main>
  )
}

/** Dibujados, no caracteres: ▶ y ❚❚ cambian de forma y de ancho según la fuente que los tenga. */
function PlayIcon({ state }: { state: 'play' | 'pause' | 'restart' }) {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
      {state === 'play' ? (
        <path d="M2.5 1.5v9l8-4.5z" />
      ) : state === 'pause' ? (
        <path d="M2.5 1.5h2.5v9H2.5zM7 1.5h2.5v9H7z" />
      ) : (
        <path d="M6 1.5a4.5 4.5 0 1 1-4.3 5.8l1.4-.4A3 3 0 1 0 6 3v1.8L3.3 2.3 6 0z" />
      )}
    </svg>
  )
}

const sectorLabel = (sector: Sector) => (sector.ref === 'rural' ? sector.name : `UPZ ${sector.name}`)

const STREAM_ORDER: WasteStream[] = ['NON_RECYCLABLE', 'RECYCLABLE', 'ORGANIC']
const ON_STREET: VehicleState[] = ['DRIVING', 'COLLECTING', 'UNLOADING']
const CONTAINER_LAYERS = ['containers', 'orphans', 'overflows']
const CLOSURE_LAYERS = ['closures', 'closure-marks']

const TOOLTIP_STYLE = {
  background: 'var(--panel)',
  color: 'var(--fg)',
  border: '1px solid var(--line)',
  borderRadius: '0',
  padding: '8px 10px',
  fontFamily: 'var(--font-ui)',
  fontSize: 'var(--text-13)',
}

function cityTooltip({ object, layer }: PickingInfo) {
  if (!object) return null
  if (layer?.id === 'localities') {
    const { locality } = object as { locality: Locality }
    return {
      className: 'tooltip',
      style: TOOLTIP_STYLE,
      html: `<strong>${locality.name}</strong>
        <span>ASE ${locality.area} · ${locality.operator}${locality.rural ? ' · rural' : ''}</span>`,
    }
  }
  if (layer?.id === 'containers') {
    const container = object as CityContainer
    const at = clock(secondsOfDay(container.shiftStart) + container.visitSeconds)
    const rules = container.rules.map((rule) => `<span class="rule">${rule}</span>`).join('')
    return {
      className: 'tooltip',
      style: TOOLTIP_STYLE,
      html: `<strong class="num">${container.code}</strong>
        <span>ASE ${container.area} · ${container.simulated ? 'simulado' : 'del operador'}</span>
        <span class="num">${container.kg} kg por recolección, por capacidad</span>
        <span class="num">Turno de ${container.shift === 'Dia' ? 'día' : 'noche'} · llega a las ${at}</span>${rules}`,
    }
  }
  if (layer?.id === 'sites') return { className: 'tooltip', style: TOOLTIP_STYLE, html: `<strong>${(object as Site).label}</strong>` }
  return null
}

function tooltipFor(current: CurrentPlan | null, live: boolean, sensors: Sensors, firstAt: Map<string, number>) {
  return (info: PickingInfo) => {
    if (info.layer?.id === 'localities' && info.object) {
      const { locality } = info.object as { locality: Locality }
      return {
        className: 'tooltip',
        style: TOOLTIP_STYLE,
        html: `<strong>${locality.name}</strong><span>Clic para verla en vivo</span>`,
      }
    }
    return current ? tooltip(info, current, live, sensors, firstAt) : null
  }
}

function tooltip(
  { object, layer }: PickingInfo,
  current: CurrentPlan,
  live: boolean,
  sensors: Sensors,
  firstAt: Map<string, number>,
) {
  if (!object || ![...CONTAINER_LAYERS, 'streets', 'sites', 'vehicles', ...CLOSURE_LAYERS].includes(layer?.id ?? ''))
    return null
  // deck.gl aplica estilos en línea a su tooltip; se reemplazan con los tokens
  return {
    className: 'tooltip',
    style: TOOLTIP_STYLE,
    html:
      layer?.id === 'streets'
        ? streetHtml(object as Street, current, firstAt)
        : layer?.id === 'sites'
        ? siteHtml(object as Site)
        : layer?.id === 'vehicles'
          ? vehicleHtml(object as VehicleMark)
          : CLOSURE_LAYERS.includes(layer?.id ?? '')
          ? closureHtml(object as ClosureEvent, current)
          : containerHtml(object as Container, current, live, sensors),
  }
}

function containerHtml(container: Container, current: CurrentPlan, live: boolean, sensors: Sensors): string {
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
    <span>${STREAM_NAMES[container.stream]} · ${current.plan.meta.simulated ? 'simulado' : 'del operador'}</span>
    <span class="num">${container.kg} kg por recolección</span>
    ${fillHtml(sensors.readings.get(container.id), sensors.liters.get(container.id))}
    <span class="num">Vehículo ${container.route} · llega a las ${at(container.visitSeconds)}</span>
    ${orphan}${spilled}${cutOff}${rules}${hint}`
}

function streetHtml(street: Street, current: CurrentPlan, firstAt: Map<string, number>): string {
  const arrival = firstAt.get(street.id)
  const at = arrival === undefined ? null : clock(secondsOfDay(current.plan.meta.shiftStart) + arrival)
  return `<strong class="num">${street.id}</strong>
    <span>Puerta a puerta · carga estimada por población</span>
    <span class="num">${decimal(street.kg)} kg en ${decimal(street.meters)} m de calle</span>
    <span class="num">Vehículo ${street.route}${at ? ` · llega a las ${at}` : ' · sin visita en el plan vigente'}</span>`
}

function fillHtml(percent: number | undefined, liters: number | undefined): string {
  if (percent === undefined || liters === undefined) return ''
  return `<span class="num fill-${fillState(percent)}">${percent >= 100 ? 'Desbordado' : `Lleno al ${percent} %`} · ${liters.toLocaleString('es-CO')} L · sensor simulado</span>`
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
