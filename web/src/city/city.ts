import type { Container, DemoPlan, LonLat, Route, Site } from '../demo/plan'

/** Una localidad del selector: su borde y la ASE que la atiende (docs/api.md, `LocalityOutline`). */
export interface Locality {
  number: number
  name: string
  area: number
  operator: string
  rural: boolean
  /** El anillo exterior de cada parte. */
  outline: LonLat[][]
}

export interface Fleet {
  trucks: number
  lowerBound: number
  sharedAcrossShifts: boolean
  declared: number | null
  warning: string | null
}

export interface Shift {
  name: string
  start: string
  seconds: number
  plan: DemoPlan
}

export interface Area {
  number: number
  name: string
  operator: string
  localities: number[]
  fleet: Fleet
  kgPerDay: number
  /** Parte de las toneladas de la ASE; null si el operador no las publica. */
  sideLoadingShare: number | null
  shifts: Shift[]
  skipped: string[]
}

export interface SelectionPlan {
  localities: number[]
  areas: Area[]
  planMs: number
}

export interface CityRoute extends Route {
  area: number
}

/** Un contenedor de la ciudad, con lo que el tooltip necesita saber de su plan. */
export interface CityContainer extends Container {
  code: string
  area: number
  shift: string
  shiftStart: string
  simulated: boolean
}

export const fetchLocalities = (): Promise<Locality[] | null> =>
  fetch('/api/localities')
    .then((response) => (response.ok ? (response.json() as Promise<Locality[]>) : null))
    .catch(() => null)

export async function fetchSelection(localities: number[], signal: AbortSignal): Promise<SelectionPlan> {
  const response = await fetch(`/api/areas?localities=${localities.join(',')}`, { signal })
  if (!response.ok) {
    const problem = (await response.json().catch(() => null)) as { detail?: string } | null
    throw new Error(problem?.detail ?? `/api/areas respondió ${response.status}`)
  }
  return (await response.json()) as SelectionPlan
}

/**
 * Todos los turnos en un solo plan para dibujarlos. Cada ASE numera sus camiones y sus contenedores simulados desde
 * 1, así que los ids llevan delante la ASE y el turno; el código original queda en `code`.
 */
export function mergePlans(selection: SelectionPlan): {
  routes: CityRoute[]
  containers: CityContainer[]
  sites: Site[]
} {
  const routes: CityRoute[] = []
  const containers: CityContainer[] = []
  const sites = new Map<string, Site>()
  for (const area of selection.areas) {
    for (const shift of area.shifts) {
      const key = (id: string) => `${area.number}·${shift.name}·${id}`
      for (const route of shift.plan.routes) routes.push({ ...route, id: key(route.id), area: area.number })
      for (const container of shift.plan.containers) {
        containers.push({
          ...container,
          id: key(container.id),
          route: key(container.route),
          code: container.id,
          area: area.number,
          shift: shift.name,
          shiftStart: shift.start,
          simulated: shift.plan.meta.simulated,
        })
      }
      // El relleno es el mismo para todas; cada ASE tiene su base.
      for (const site of shift.plan.sites) sites.set(`${site.position[0]},${site.position[1]}`, site)
    }
  }
  return { routes, containers, sites: [...sites.values()] }
}

/** `#ciudad=11,10`: la selección queda en la dirección para compartirla o volver a ella. */
export function selectionFromHash(hash = window.location.hash): number[] | null {
  const match = /^#ciudad(?:=([\d,]*))?$/.exec(hash)
  if (!match) return null
  const numbers = (match[1] ?? '').split(',').map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 20)
  return [...new Set(numbers)].sort((a, b) => a - b)
}

export function hashOf(selection: number[] | null): string {
  return selection === null ? '' : `#ciudad${selection.length > 0 ? `=${selection.join(',')}` : ''}`
}

/** El rectángulo que encierra los bordes, como lo pide fitBounds. */
export function boundsOf(rings: LonLat[][]): [[number, number], [number, number]] {
  let west = Infinity
  let south = Infinity
  let east = -Infinity
  let north = -Infinity
  for (const ring of rings) {
    for (const [lon, lat] of ring) {
      west = Math.min(west, lon)
      east = Math.max(east, lon)
      south = Math.min(south, lat)
      north = Math.max(north, lat)
    }
  }
  return [
    [west, south],
    [east, north],
  ]
}
