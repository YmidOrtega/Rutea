import { loadDemoPlan, type DemoPlan, type Route } from '../demo/plan'

/** Una avería y su replanificación, como las difunde la API por WebSocket (R05, R08). */
export interface BreakdownEvent {
  type: 'breakdown'
  /** Orden del evento en la sesión del servidor. */
  seq: number
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
  container: string
  atSeconds: number
  /** "reporte", o "avería de C-03" si el camión que lo iba a recoger se averió. */
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

export type OperationEvent = BreakdownEvent | OverflowEvent

export interface LoadedPlan {
  plan: DemoPlan
  /** Con la API detrás se puede simular averías; con el plan estático, solo mirar. */
  live: boolean
  /** Lo que ya ocurrió en la sesión del servidor. */
  events: OperationEvent[]
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
      const events = await fetch('/api/events').then((r) => (r.ok ? (r.json() as Promise<OperationEvent[]>) : []))
      return { plan, live: true, events }
    }
  } catch {
    // Sin API: se sigue con el plan estático.
  } finally {
    clearTimeout(timer)
  }
  return { plan: await loadDemoPlan(), live: false, events: [] }
}

/** La avería y, después, los desbordes que ese camión iba a recoger y ahora recoge otro. */
export const requestBreakdown = (truck: string, atSeconds: number) =>
  post<OperationEvent[]>('/api/breakdowns', { truck, atSeconds: Math.floor(atSeconds) })

export const requestOverflow = (container: string, atSeconds: number) =>
  post<OverflowEvent>('/api/overflows', { container, atSeconds: Math.floor(atSeconds) })

async function post<T extends object>(url: string, request: object): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  const body = (await response.json()) as T | { error: string }
  if (!response.ok || 'error' in body) {
    throw new Error('error' in body ? body.error : `la API respondió ${response.status}`)
  }
  return body
}

/** Escucha los eventos de la operación; reconecta con espera creciente si se cae. Devuelve cómo cerrar. */
export function listenEvents(onEvent: (event: OperationEvent) => void): () => void {
  let socket: WebSocket | null = null
  let retry = 1_000
  let closed = false
  let timer = 0
  const connect = () => {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    socket = new WebSocket(`${scheme}://${window.location.host}/ws/events`)
    socket.onopen = () => {
      retry = 1_000
    }
    socket.onmessage = (message) => onEvent(JSON.parse(String(message.data)) as OperationEvent)
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


/** El camión averiado se detiene donde estaba: su recorrido se corta a la hora de la avería. */
export function stopAt(route: Route, atSeconds: number): Route {
  const keep = route.times.findIndex((t) => t > atSeconds)
  if (keep === -1) return route
  const end = Math.max(1, keep)
  return { ...route, path: route.path.slice(0, end), times: route.times.slice(0, end) }
}

export interface CurrentPlan {
  /** El plan con los turnos que cambiaron por un desborde y las horas de visita corridas. */
  plan: DemoPlan
  /** Rutas de reserva, de averías y de desbordes. */
  reserve: Route[]
  /** Contenedor huérfano → la reserva que lo recoge tras la avería. */
  orphanRoute: Map<string, string>
  /** Contenedor → su último desborde. */
  overflows: Map<string, OverflowEvent>
  /** Compactador → hora de su avería. */
  brokenAt: Map<string, number>
}

/** El plan vigente después de los eventos, aplicados en el orden del servidor. */
export function currentPlan(plan: DemoPlan, events: OperationEvent[]): CurrentPlan {
  const routes = new Map(plan.routes.map((route) => [route.id, route]))
  const visits = new Map<string, number>()
  const reserve: Route[] = []
  const orphanRoute = new Map<string, string>()
  const overflows = new Map<string, OverflowEvent>()
  const brokenAt = new Map<string, number>()
  for (const event of [...events].sort((a, b) => a.seq - b.seq)) {
    if (event.type === 'breakdown') {
      brokenAt.set(event.truck, event.atSeconds)
      reserve.push(...event.reserve)
      for (const route of event.reserve) for (const stop of route.stops) orphanRoute.set(stop, route.id)
      continue
    }
    overflows.set(event.container, event)
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
  return { plan: { ...plan, routes: [...routes.values()], containers }, reserve, orphanRoute, overflows, brokenAt }
}

/** Las rutas de reserva, tras una avería o un desborde, empiezan por R- (R-03-1, R-N0123). */
export const isReserveId = (id: string) => id.startsWith('R-')

/** Quién recoge un desborde, en una frase: la usan el tooltip, la tarjeta y el panel. */
export function overflowPickup(event: OverflowEvent, at: (seconds: number) => string): string {
  const minutes = Math.round((event.pickupSeconds - event.atSeconds) / 60)
  switch (event.pickup) {
    case 'PLANNED':
      return `lo recoge ${event.truck} en su visita del plan a las ${at(event.pickupSeconds)} (${minutes} min)`
    case 'IN_SERVICE':
      return `${event.truck} se desvía ${(event.extraMeters / 1000).toFixed(1)} km y llega a las ${at(event.pickupSeconds)} (${minutes} min)`
    case 'RESERVE':
      return `ningún compactador tiene cupo; sale ${event.truck} y llega a las ${at(event.pickupSeconds)} (${minutes} min)`
  }
}
