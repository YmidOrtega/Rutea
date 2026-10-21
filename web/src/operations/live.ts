import { loadDemoPlan, type DemoPlan, type Route } from '../demo/plan'

/** Una avería y su replanificación, como las difunde la API por WebSocket (R05, R08). */
export interface BreakdownEvent {
  truck: string
  atSeconds: number
  dispatchSeconds: number
  latencyMs: number
  orphans: string[]
  /** Rutas de reserva (R-01…), con los tiempos ya corridos a la hora de despacho. */
  reserve: Route[]
  firstPickupSeconds: number
  lastPickupSeconds: number
  meetsRecoveryRule: boolean
  citation: string
}

export interface LoadedPlan {
  plan: DemoPlan
  /** Con la API detrás se puede simular averías; con el plan estático, solo mirar. */
  live: boolean
  /** Las averías que ya ocurrieron en la sesión del servidor. */
  events: BreakdownEvent[]
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
      const events = await fetch('/api/events').then((r) => (r.ok ? (r.json() as Promise<BreakdownEvent[]>) : []))
      return { plan, live: true, events }
    }
  } catch {
    // Sin API: se sigue con el plan estático.
  } finally {
    clearTimeout(timer)
  }
  return { plan: await loadDemoPlan(), live: false, events: [] }
}

export async function requestBreakdown(truck: string, atSeconds: number): Promise<BreakdownEvent> {
  const response = await fetch('/api/breakdowns', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ truck, atSeconds: Math.floor(atSeconds) }),
  })
  const body = (await response.json()) as BreakdownEvent | { error: string }
  if (!response.ok || 'error' in body) {
    throw new Error('error' in body ? body.error : `la API respondió ${response.status}`)
  }
  return body
}

/** Escucha los eventos de la operación; reconecta con espera creciente si se cae. Devuelve cómo cerrar. */
export function listenEvents(onEvent: (event: BreakdownEvent) => void): () => void {
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
    socket.onmessage = (message) => onEvent(JSON.parse(String(message.data)) as BreakdownEvent)
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

export const eventKey = (event: BreakdownEvent) => `${event.truck}@${event.atSeconds}`

/** El camión averiado se detiene donde estaba: su recorrido se corta a la hora de la avería. */
export function stopAt(route: Route, atSeconds: number): Route {
  const keep = route.times.findIndex((t) => t > atSeconds)
  if (keep === -1) return route
  const end = Math.max(1, keep)
  return { ...route, path: route.path.slice(0, end), times: route.times.slice(0, end) }
}
