import type { WasteStream } from '../demo/plan'
import type { CurrentPlan, OperationEvent } from './live'

/** La llegada a un contenedor o a un trozo de calle. */
export interface Visit {
  stop: string
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
  /** La primera visita de cada contenedor, en orden. */
  firstSeconds: number[]
  /** La primera visita de cada trozo de calle, en orden, y metersBefore[i] los metros de los primeros i. */
  streetSeconds: number[]
  metersBefore: number[]
  lastSeconds: number
}

/** Las visitas del plan vigente: con los desvíos y las reservas, y sin lo que un camión averiado ya no hace. */
export function progressOf(current: CurrentPlan, events: OperationEvent[]): Progress {
  const brokenAt = new Map(events.flatMap((event) => (event.type === 'breakdown' ? [[event.truck, event.atSeconds]] : [])))
  const kg = new Map(current.plan.containers.map((container) => [container.id, container.kg]))
  const meters = new Map(current.plan.streets.map((street) => [street.id, street.meters]))
  for (const street of current.plan.streets) kg.set(street.id, street.kg)
  const visits: Visit[] = []
  const first = new Map<string, number>()
  for (const route of [...current.plan.routes, ...current.reserve]) {
    const until = brokenAt.get(route.id) ?? Infinity
    route.arrivals.forEach((seconds, k) => {
      if (seconds >= until) return
      const stop = route.stops[k]
      visits.push({ stop, route: route.id, stream: route.stream, seconds, kg: kg.get(stop) ?? 0 })
      first.set(stop, Math.min(seconds, first.get(stop) ?? Infinity))
    })
  }
  visits.sort((a, b) => a.seconds - b.seconds)
  const kgBefore = [0]
  for (const visit of visits) kgBefore.push(kgBefore[kgBefore.length - 1] + visit.kg)
  const streets = [...first].filter(([stop]) => meters.has(stop)).sort((a, b) => a[1] - b[1])
  const metersBefore = [0]
  for (const [stop] of streets) metersBefore.push(metersBefore[metersBefore.length - 1] + meters.get(stop)!)
  return {
    visits,
    visitSeconds: visits.map((visit) => visit.seconds),
    kgBefore,
    firstSeconds: [...first].filter(([stop]) => !meters.has(stop)).map(([, seconds]) => seconds).sort((a, b) => a - b),
    streetSeconds: streets.map(([, seconds]) => seconds),
    metersBefore,
    lastSeconds: visits.at(-1)?.seconds ?? 0,
  }
}

export interface Collected {
  containers: number
  kg: number
  meters: number
}

export function collectedBy(progress: Progress, seconds: number): Collected {
  return {
    containers: countUpTo(progress.firstSeconds, seconds),
    kg: progress.kgBefore[countUpTo(progress.visitSeconds, seconds)],
    meters: progress.metersBefore[countUpTo(progress.streetSeconds, seconds)],
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
  progress.visits.find((visit) => visit.stop === container)
