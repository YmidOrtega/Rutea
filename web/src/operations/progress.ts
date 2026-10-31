import type { LonLat, Route, WasteStream } from '../demo/plan'
import type { CurrentPlan, OperationEvent } from './live'

export interface Visit {
  container: string
  route: string
  stream: WasteStream
  seconds: number
  kg: number
}

export interface Progress {
  /** Todas las visitas, en orden de hora: tras un desvío por desborde, el camión del plan igual pasa después. */
  visits: Visit[]
  /** La hora de cada visita, para buscarla en cada cuadro sin recorrer las visitas. */
  visitSeconds: number[]
  /** kgBefore[i]: kilos de las primeras i visitas; el último es lo que recoge el plan vigente. */
  kgBefore: number[]
  firstSeconds: number[]
  lastSeconds: number
}

/**
 * Hora de llegada a cada parada, en el orden de `route.stops`. Una parada es un punto que se repite en el
 * recorrido con la hora en que el camión vuelve a arrancar; las del relleno y la ECA son descargas. Es la misma
 * regla de Vehicle.arrivals en el servidor.
 */
export function arrivals(route: Route, unloadSites: LonLat[]): number[] {
  const { path, times } = route
  const found: number[] = []
  for (let i = 0; i + 1 < times.length; i++) {
    const [lon, lat] = path[i]
    if (lon !== path[i + 1][0] || lat !== path[i + 1][1] || times[i + 1] <= times[i]) continue
    if (unloadSites.some(([siteLon, siteLat]) => siteLon === lon && siteLat === lat)) continue
    found.push(times[i])
  }
  return found
}

/** Las visitas del plan vigente: con los desvíos y las reservas, y sin lo que un camión averiado ya no hace. */
export function progressOf(current: CurrentPlan, events: OperationEvent[]): Progress {
  const unloadSites = current.plan.sites.filter((site) => site.id !== 'base').map((site) => site.position)
  const brokenAt = new Map(events.flatMap((event) => (event.type === 'breakdown' ? [[event.truck, event.atSeconds]] : [])))
  const kg = new Map(current.plan.containers.map((container) => [container.id, container.kg]))
  const visits: Visit[] = []
  const first = new Map<string, number>()
  for (const route of [...current.plan.routes, ...current.reserve]) {
    const until = brokenAt.get(route.id) ?? Infinity
    arrivals(route, unloadSites).forEach((seconds, k) => {
      if (seconds >= until) return
      const container = route.stops[k]
      visits.push({ container, route: route.id, stream: route.stream, seconds, kg: kg.get(container) ?? 0 })
      first.set(container, Math.min(seconds, first.get(container) ?? Infinity))
    })
  }
  visits.sort((a, b) => a.seconds - b.seconds)
  const kgBefore = [0]
  for (const visit of visits) kgBefore.push(kgBefore[kgBefore.length - 1] + visit.kg)
  return {
    visits,
    visitSeconds: visits.map((visit) => visit.seconds),
    kgBefore,
    firstSeconds: [...first.values()].sort((a, b) => a - b),
    lastSeconds: visits.at(-1)?.seconds ?? 0,
  }
}

export function collectedBy(progress: Progress, seconds: number): { containers: number; kg: number } {
  return {
    containers: countUpTo(progress.firstSeconds, seconds),
    kg: progress.kgBefore[countUpTo(progress.visitSeconds, seconds)],
  }
}

function countUpTo(sorted: number[], seconds: number): number {
  let low = 0
  let high = sorted.length
  while (low < high) {
    const mid = (low + high) >>> 1
    if (sorted[mid] <= seconds) low = mid + 1
    else high = mid
  }
  return low
}

/** La primera llegada a un contenedor en el plan vigente: tras una avería, la de su reserva. */
export const firstVisit = (progress: Progress, container: string): Visit | undefined =>
  progress.visits.find((visit) => visit.container === container)
