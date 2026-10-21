
export type WasteStream = 'RECYCLABLE' | 'ORGANIC' | 'NON_RECYCLABLE'

/** [lon, lat], el orden de MapLibre y deck.gl. */
export type LonLat = [number, number]

/**
 * Sin estado de llenado: no hay sensores hasta F5, y un porcentaje inventado no sería honesto. `rules` dice, en
 * texto, lo que la norma exige en esta parada (R02, R07).
 */
export interface Container {
  id: string
  stream: WasteStream
  position: LonLat
  kg: number
  route: string
  visitSeconds: number
  noCompaction: boolean
  nightNoise: boolean
  rules: string[]
}

/**
 * Una regla de docs/normativa.md, cuántas paradas toca en el plan y qué significa: se cumple (OK), exige que el
 * conductor haga algo distinto en esas paradas (ACTION) o el plan no cumple (FAIL).
 */
export interface ComplianceCheck {
  id: string
  citation: string
  count: number
  summary: string
  status: 'OK' | 'ACTION' | 'FAIL'
}

/**
 * El turno de un compactador: base → viaje → descarga en el relleno → … → base. `tripLoadsKg[i]` es la carga
 * del viaje `i`. `times[i]`: segundos desde la salida hasta `path[i]`; en cada parada (contenedor o descarga)
 * el punto se repite con la hora en que el camión vuelve a arrancar.
 */
export interface Route {
  id: string
  stream: WasteStream
  capacityKg: number
  loadKg: number
  distanceMeters: number
  durationSeconds: number
  tripLoadsKg: number[]
  stops: string[]
  path: LonLat[]
  times: number[]
}

export interface Site {
  id: 'relleno' | 'base' | 'eca'
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
    shiftSeconds: number
    serviceSeconds: number
    unloadSeconds: number
    /** Consumo supuesto: el CO₂ es estimado, no medido. */
    litersPerKm: number
    /** Factor del IPCC 2006 para el diésel. */
    kgCo2PerLiter: number
    attribution: string
    /** Lo que el plan supone sin dato publicado; el panel lo muestra tal cual. */
    assumptions: string[]
  }
  sites: Site[]
  containers: Container[]
  routes: Route[]
  compliance: ComplianceCheck[]
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

/** Los tiempos salen de las velocidades por tipo de vía del grafo y de la parada en cada contenedor. */
export function toTrips(routes: Route[]): Trip[] {
  return routes.map((route) => ({ route, timestamps: route.times }))
}

/** "18:00" → segundos desde la medianoche. */
export function secondsOfDay(hhmm: string): number {
  const [hours, minutes] = hhmm.split(':').map(Number)
  return hours * 3600 + minutes * 60
}
