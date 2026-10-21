
export type WasteStream = 'RECYCLABLE' | 'ORGANIC' | 'NON_RECYCLABLE'

/** [lon, lat], el orden de MapLibre y deck.gl. */
export type LonLat = [number, number]

/** Sin estado de llenado: no hay sensores hasta F5, y un porcentaje inventado no sería honesto. */
export interface Container {
  id: string
  stream: WasteStream
  position: LonLat
  kg: number
  route: string
}

/** Un viaje: sale del relleno, recoge y vuelve a descargar. `times[i]`: segundos hasta `path[i]`. */
export interface Route {
  id: string
  stream: WasteStream
  capacityKg: number
  loadKg: number
  distanceMeters: number
  durationSeconds: number
  stops: string[]
  path: LonLat[]
  times: number[]
}

export interface Site {
  id: 'relleno' | 'base'
  label: string
  position: LonLat
}

export interface DemoPlan {
  meta: {
    zone: string
    simulated: boolean
    network: string
    solver: string
    seed: number
    iterations: number
    shiftStart: string
    attribution: string
  }
  sites: Site[]
  containers: Container[]
  routes: Route[]
}

export interface Trip {
  route: Route
  timestamps: number[]
}

export const STREAM_NAMES: Record<WasteStream, string> = {
  RECYCLABLE: 'Aprovechables',
  ORGANIC: 'Orgánicos aprovechables',
  NON_RECYCLABLE: 'No aprovechables',
}

export async function loadDemoPlan(url = '/demo/plan-suba.json'): Promise<DemoPlan> {
  const response = await fetch(url)
  if (!response.ok) {
    throw new Error(`${url} respondió ${response.status}`)
  }
  return (await response.json()) as DemoPlan
}

/** Los tiempos salen de las velocidades por tipo de vía del grafo; aún sin tiempo de servicio por contenedor. */
export function toTrips(routes: Route[]): Trip[] {
  return routes.map((route) => ({ route, timestamps: route.times }))
}

/** "18:00" → segundos desde la medianoche. */
export function secondsOfDay(hhmm: string): number {
  const [hours, minutes] = hhmm.split(':').map(Number)
  return hours * 3600 + minutes * 60
}
