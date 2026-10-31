import { km } from '../format'
import { loadDemoPlan, type DemoPlan, type LonLat, type Route } from '../demo/plan'
import { apiError, fetchSensors, fetchTelemetry, type SensorsMessage, type TelemetryMessage } from './telemetry'

/** Una avería y su replanificación, como las difunde la API por WebSocket (R05, R08). */
export interface BreakdownEvent {
  type: 'breakdown'
  seq: number
  area: number
  truck: string
  atSeconds: number
  dispatchSeconds: number
  latencyMs: number
  orphans: string[]
  /** Rutas de reserva (R-03-1… para C-03), con los tiempos ya corridos a la hora de despacho. */
  reserve: Route[]
  firstPickupSeconds: number
  lastPickupSeconds: number
  meetsRecoveryRule: boolean
  citation: string
}

/**
 * Un contenedor desbordado y quién lo recoge: su visita del plan, el compactador en servicio que llega primero
 * (con su turno redibujado) o una reserva desde la base.
 */
export interface OverflowEvent {
  type: 'overflow'
  seq: number
  area: number
  container: string
  atSeconds: number
  /** "reporte", "sensor" si lo detectó su sensor de llenado, o "avería de C-03" si el camión que lo iba a recoger se averió. */
  cause: string
  pickup: 'PLANNED' | 'IN_SERVICE' | 'RESERVE'
  truck: string
  pickupSeconds: number
  extraMeters: number
  latencyMs: number
  routes: Route[]
  /** Hora nueva de llegada a cada contenedor del plan del compactador que se desvió. */
  visits: Record<string, number>
}

/** Una vía cerrada: los compactadores que pasaban por ella se desvían y sus turnos se redibujan. */
export interface ClosureEvent {
  type: 'closure'
  seq: number
  area: number
  atSeconds: number
  roadClass: string
  path: LonLat[]
  latencyMs: number
  /** Filas más columnas de la matriz recalculadas, de matrixSize. */
  recomputed: number
  matrixSize: number
  changedPairs: number
  isolated: string[]
  /** Aislados con una visita pendiente, que sale del plan. */
  dropped: string[]
  changes: { truck: string; extraMeters: number; extraSeconds: number; rerouted: boolean; late: boolean }[]
  routes: Route[]
  visits: Record<string, number>
  citation: string
}

export type OperationEvent = BreakdownEvent | OverflowEvent | ClosureEvent

/** Eventos de un turno; si el turno no es el que se conocía, empezó otro. */
export interface EventsPage {
  shift: number
  events: OperationEvent[]
}

/** Llega al conectarse y cuando empieza un turno nuevo: con él se sabe si faltan eventos o hay que olvidarlos. */
export interface ShiftMessage {
  type: 'shift'
  shift: number
  area: number
  /** El último evento del turno; 0 si todavía no hay ninguno. */
  seq: number
}

export const fetchEvents = (after = 0) =>
  fetch(`/api/events?after=${after}`).then((r) => (r.ok ? (r.json() as Promise<EventsPage>) : null))

export interface LoadedPlan {
  plan: DemoPlan
  /** Con la API detrás se puede simular averías; con el plan estático, solo mirar. */
  live: boolean
  events: EventsPage | null
  /** Los vehículos en la calle y el reloj del servidor; sin API, el mapa reproduce el plan por su cuenta. */
  telemetry: TelemetryMessage | null
  sensors: SensorsMessage | null
}

/** Recién arrancada, la API tarda unos 27 s en armar el plan. */
const API_TIMEOUT_MS = 60_000

export async function loadPlan(): Promise<LoadedPlan> {
  const abort = new AbortController()
  const timer = setTimeout(() => abort.abort(), API_TIMEOUT_MS)
  try {
    const response = await fetch('/api/plan', { signal: abort.signal })
    if (response.ok) {
      const plan = (await response.json()) as DemoPlan
      const [events, telemetry, sensors] = await Promise.all([
        fetchEvents(),
        fetchTelemetry(),
        fetchSensors(),
      ])
      return { plan, live: true, events, telemetry, sensors }
    }
  } catch {
    // Sin API: se sigue con el plan estático.
  } finally {
    clearTimeout(timer)
  }
  return { plan: await loadDemoPlan(), live: false, events: null, telemetry: null, sensors: null }
}

/** La avería y, después, los desbordes que ese camión iba a recoger y ahora recoge otro. Todo a la hora del servidor. */
export const requestBreakdown = (truck: string) => post<OperationEvent[]>('/api/breakdowns', { truck })

export const requestOverflow = (container: string) => post<OverflowEvent>('/api/overflows', { container })

export const requestClosure = ([lon, lat]: LonLat) => post<ClosureEvent>('/api/closures', { lon, lat })

async function post<T extends object>(url: string, request: object): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  if (!response.ok) throw await apiError(response)
  return (await response.json()) as T
}

export interface Listeners {
  event: (event: OperationEvent) => void
  telemetry: (message: TelemetryMessage) => void
  sensors: (message: SensorsMessage) => void
  /** El turno al conectarse o al empezar uno nuevo; reconnected si ya se había conectado antes y se cayó. */
  shift: (message: ShiftMessage, reconnected: boolean) => void
}

/** Escucha la operación; reconecta con espera creciente si se cae. Devuelve cómo cerrar. */
export function listen(listeners: Listeners): () => void {
  let socket: WebSocket | null = null
  let retry = 1_000
  let closed = false
  let timer = 0
  let opened = 0
  const connect = () => {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    socket = new WebSocket(`${scheme}://${window.location.host}/ws/events`)
    socket.onopen = () => {
      retry = 1_000
      opened++
    }
    socket.onmessage = (message) => {
      const data = JSON.parse(String(message.data)) as
        | OperationEvent
        | TelemetryMessage
        | SensorsMessage
        | ShiftMessage
      if (data.type === 'telemetry') listeners.telemetry(data)
      else if (data.type === 'sensors') listeners.sensors(data)
      else if (data.type === 'shift') listeners.shift(data, opened > 1)
      else listeners.event(data)
    }
    socket.onclose = () => {
      if (!closed) {
        timer = window.setTimeout(connect, retry)
        retry = Math.min(retry * 2, 30_000)
      }
    }
  }
  connect()
  return () => {
    closed = true
    window.clearTimeout(timer)
    socket?.close()
  }
}


export interface CurrentPlan {
  /** El plan con los turnos que cambiaron por un desborde y las horas de visita corridas. */
  plan: DemoPlan
  reserve: Route[]
  /** Contenedor huérfano → la reserva que lo recoge tras la avería. */
  orphanRoute: Map<string, string>
  overflows: Map<string, OverflowEvent>
  closures: ClosureEvent[]
  isolated: Map<string, ClosureEvent>
}

/** El plan vigente después de los eventos, aplicados en el orden del servidor. */
export function currentPlan(plan: DemoPlan, events: OperationEvent[]): CurrentPlan {
  const routes = new Map(plan.routes.map((route) => [route.id, route]))
  const visits = new Map<string, number>()
  const reserve: Route[] = []
  const orphanRoute = new Map<string, string>()
  const overflows = new Map<string, OverflowEvent>()
  const closures: ClosureEvent[] = []
  const isolated = new Map<string, ClosureEvent>()
  for (const event of [...events].sort((a, b) => a.seq - b.seq)) {
    if (event.type === 'breakdown') {
      reserve.push(...event.reserve)
      for (const route of event.reserve) for (const stop of route.stops) orphanRoute.set(stop, route.id)
      continue
    }
    if (event.type === 'closure') {
      closures.push(event)
      for (const container of event.isolated) isolated.set(container, event)
    } else {
      overflows.set(event.container, event)
    }
    for (const route of event.routes) {
      if (isReserveId(route.id)) reserve.push(route)
      else routes.set(route.id, route)
    }
    for (const [container, seconds] of Object.entries(event.visits)) visits.set(container, seconds)
  }
  const containers = plan.containers.map((container) => {
    const seconds = visits.get(container.id)
    return seconds === undefined ? container : { ...container, visitSeconds: seconds }
  })
  return {
    plan: { ...plan, routes: [...routes.values()], containers },
    reserve,
    orphanRoute,
    overflows,
    closures,
    isolated,
  }
}

/** Las rutas de reserva, tras una avería o un desborde, empiezan por R- (R-03-1, R-N0123). */
export const isReserveId = (id: string) => id.startsWith('R-')

export function overflowPickup(event: OverflowEvent, at: (seconds: number) => string): string {
  const minutes = Math.round((event.pickupSeconds - event.atSeconds) / 60)
  switch (event.pickup) {
    case 'PLANNED':
      return `lo recoge ${event.truck} en su visita del plan a las ${at(event.pickupSeconds)} (${minutes} min)`
    case 'IN_SERVICE':
      return `${event.truck} se desvía ${km(event.extraMeters)} km y llega a las ${at(event.pickupSeconds)} (${minutes} min)`
    case 'RESERVE':
      return `ningún compactador tiene cupo; sale ${event.truck} y llega a las ${at(event.pickupSeconds)} (${minutes} min)`
  }
}

const ROAD_CLASSES: Record<string, string> = {
  MOTORWAY: 'autopista',
  TRUNK: 'vía troncal',
  PRIMARY: 'vía primaria',
  SECONDARY: 'vía secundaria',
  TERTIARY: 'vía terciaria',
  UNCLASSIFIED: 'vía sin clasificar',
  RESIDENTIAL: 'calle residencial',
  LIVING_STREET: 'calle peatonal',
  SERVICE: 'vía de servicio',
  TRACK: 'trocha',
}

export const roadClassName = (roadClass: string) => ROAD_CLASSES[roadClass] ?? roadClass.toLowerCase()
