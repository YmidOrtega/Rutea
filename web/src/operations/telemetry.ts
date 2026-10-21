import type { LonLat, Trip, WasteStream } from '../demo/plan'

/** El reloj de la simulación, del servidor: todos los clientes ven la misma hora. */
export interface SimClock {
  seconds: number
  speed: number
  running: boolean
  endSeconds: number
}

export type VehicleState = 'DRIVING' | 'COLLECTING' | 'UNLOADING' | 'BROKEN' | 'DONE'

export interface TelemetryMessage {
  type: 'telemetry'
  clock: SimClock
  /** Solo los que cambiaron; cada uno con lo recorrido desde el reporte anterior, [lon, lat, segundos]. */
  vehicles: { id: string; stream: WasteStream; state: VehicleState; points: [number, number, number][] }[]
}

export interface VehicleTrack {
  id: string
  stream: WasteStream
  state: VehicleState
  path: LonLat[]
  times: number[]
}

export interface Feed {
  clock: SimClock
  /** performance.now() al recibir el reloj: desde ahí se estima la hora mientras llega el siguiente reporte. */
  receivedAt: number
  vehicles: Map<string, VehicleTrack>
}

/** Igual que Telemetry.REPORT_MILLIS en el servidor. */
const REPORT_SECONDS = 0.25
/** La estela que dibuja el mapa; lo anterior se descarta. */
const TRAIL_SECONDS = 900

export const emptyFeed = (clock: SimClock): Feed => ({ clock, receivedAt: performance.now(), vehicles: new Map() })

export function applyTelemetry(feed: Feed, message: TelemetryMessage): Feed {
  const vehicles = new Map(feed.vehicles)
  for (const report of message.vehicles) {
    const previous = vehicles.get(report.id)
    const path = previous ? [...previous.path] : []
    const times = previous ? [...previous.times] : []
    for (const [lon, lat, seconds] of report.points) {
      if (times.length > 0 && seconds < times[times.length - 1]) continue
      path.push([lon, lat])
      times.push(seconds)
    }
    const keep = times.findIndex((seconds) => seconds >= message.clock.seconds - TRAIL_SECONDS)
    const from = Math.max(0, Math.min(keep, times.length - 2))
    vehicles.set(report.id, {
      id: report.id,
      stream: report.stream,
      state: report.state,
      path: path.slice(from),
      times: times.slice(from),
    })
  }
  return { clock: message.clock, receivedAt: performance.now(), vehicles }
}

/**
 * La hora que se dibuja: la del servidor más lo que corrió desde el último reporte, un reporte atrás. Así el mapa
 * siempre interpola entre puntos que ya llegaron y nunca adivina hacia dónde va un camión.
 */
export function feedTime(feed: Feed, now: number): number {
  const { clock } = feed
  if (!clock.running) return clock.seconds
  const elapsed = ((now - feed.receivedAt) / 1000) * clock.speed
  return Math.min(clock.seconds, clock.seconds + elapsed - REPORT_SECONDS * clock.speed)
}

export function feedTrips(feed: Feed): Trip[] {
  return [...feed.vehicles.values()].map((vehicle) => ({
    id: vehicle.id,
    stream: vehicle.stream,
    path: vehicle.path,
    timestamps: vehicle.times,
  }))
}

/** Dónde está cada vehículo a esa hora, sobre lo que reportó. */
export function positionAt(vehicle: VehicleTrack, seconds: number): LonLat {
  const { path, times } = vehicle
  let i = times.length - 1
  while (i > 0 && times[i] > seconds) i--
  if (i === times.length - 1 || times[i + 1] === times[i]) return path[i]
  const f = Math.max(0, Math.min(1, (seconds - times[i]) / (times[i + 1] - times[i])))
  return [path[i][0] + f * (path[i + 1][0] - path[i][0]), path[i][1] + f * (path[i + 1][1] - path[i][1])]
}

export const fetchTelemetry = () =>
  fetch('/api/telemetry').then((response) => (response.ok ? (response.json() as Promise<TelemetryMessage>) : null))

export async function setClock(running: boolean, speed: number): Promise<TelemetryMessage> {
  const response = await fetch('/api/clock', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ running, speed }),
  })
  const body = (await response.json()) as TelemetryMessage | { error: string }
  if (!response.ok || 'error' in body) {
    throw new Error('error' in body ? body.error : `la API respondió ${response.status}`)
  }
  return body
}

export const VEHICLE_STATES: Record<VehicleState, string> = {
  DRIVING: 'en camino',
  COLLECTING: 'recogiendo',
  UNLOADING: 'descargando',
  BROKEN: 'averiado',
  DONE: 'terminó su turno',
}
