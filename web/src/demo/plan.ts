/** Plan simulado que genera `npm run demo:data` (DemoPlanExporter en rutea-app). */

export type WasteStream = 'RECYCLABLE' | 'ORGANIC' | 'NON_RECYCLABLE'

/** [lon, lat], el orden de MapLibre y deck.gl. */
export type LonLat = [number, number]

export interface Container {
  id: string
  stream: WasteStream
  position: LonLat
  /** Llenado entre 0 y 1. */
  fill: number
  kg: number
  route: string
}

export interface Route {
  id: string
  stream: WasteStream
  capacityKg: number
  loadKg: number
  distanceMeters: number
  stops: string[]
  path: LonLat[]
}

export interface DemoPlan {
  meta: {
    zone: string
    simulated: boolean
    network: string
    blockMeters: number
    solver: string
    seed: number
  }
  depot: LonLat
  containers: Container[]
  routes: Route[]
}

/** Recorrido con la hora de paso por cada vértice, para `TripsLayer`. */
export interface Trip {
  route: Route
  /** Segundos desde la salida del depósito. */
  timestamps: number[]
}

/** Nombres oficiales de las corrientes (Resolución 2184 de 2019, art. 4). */
export const STREAM_NAMES: Record<WasteStream, string> = {
  RECYCLABLE: 'Aprovechables',
  ORGANIC: 'Orgánicos aprovechables',
  NON_RECYCLABLE: 'No aprovechables',
}

/** Velocidad supuesta del camión. Sin tiempos de servicio en las paradas: es un demo. */
export const SPEED_MPS = 18 / 3.6

const METERS_PER_DEGREE = 111_320

export async function loadDemoPlan(url = '/demo/plan-suba.json'): Promise<DemoPlan> {
  const response = await fetch(url)
  if (!response.ok) {
    throw new Error(`${url} respondió ${response.status}`)
  }
  return (await response.json()) as DemoPlan
}

/** Distancia aproximada en metros entre dos puntos cercanos (equirectangular). */
function meters([lon1, lat1]: LonLat, [lon2, lat2]: LonLat): number {
  const x = (lon2 - lon1) * Math.cos((((lat1 + lat2) / 2) * Math.PI) / 180)
  const y = lat2 - lat1
  return Math.hypot(x, y) * METERS_PER_DEGREE
}

export function toTrips(routes: Route[]): Trip[] {
  return routes.map((route) => {
    const timestamps = [0]
    for (let i = 1; i < route.path.length; i++) {
      timestamps.push(timestamps[i - 1] + meters(route.path[i - 1], route.path[i]) / SPEED_MPS)
    }
    return { route, timestamps }
  })
}
