import type { Route, WasteStream } from '../demo/plan'
import type { CurrentPlan, OperationEvent } from './live'

export interface Truck {
  id: string
  stream: WasteStream
  capacityKg: number
  changeSeconds: number[]
  loadKg: number[]
  unloads: [number, number][]
  endSeconds: number
  brokenAt: number
}

export type TruckState = 'collecting' | 'unloading' | 'broken' | 'done'

export function fleetOf(current: CurrentPlan, events: OperationEvent[]): Truck[] {
  const brokenAt = new Map(events.flatMap((event) => (event.type === 'breakdown' ? [[event.truck, event.atSeconds]] : [])))
  const kg = new Map(current.plan.containers.map((container) => [container.id, container.kg]))
  for (const street of current.plan.streets) kg.set(street.id, street.kg)
  return [...current.plan.routes, ...current.reserve].map((route) =>
    truckOf(route, kg, brokenAt.get(route.id) ?? Infinity),
  )
}

function truckOf(route: Route, kg: Map<string, number>, brokenAt: number): Truck {
  const stopKg = route.stops.map((stop) => kg.get(stop) ?? 0)
  const unloads = tripEnds(route, stopKg).map((k) =>
    longestDwell(route, route.arrivals[k], route.arrivals[k + 1] ?? Infinity),
  )
  const changes: [number, number | null][] = route.arrivals
    .map((seconds, k): [number, number | null] => [seconds, stopKg[k]])
    .filter(([seconds]) => seconds < brokenAt)
  for (const [, end] of unloads) if (end < brokenAt) changes.push([end, null])
  changes.sort((a, b) => a[0] - b[0])
  const changeSeconds: number[] = []
  const loadKg: number[] = []
  let load = 0
  for (const [seconds, added] of changes) {
    load = added === null ? 0 : load + added
    changeSeconds.push(seconds)
    loadKg.push(load)
  }
  return {
    id: route.id,
    stream: route.stream,
    capacityKg: route.capacityKg,
    changeSeconds,
    loadKg,
    unloads,
    endSeconds: route.times.at(-1) ?? 0,
    brokenAt,
  }
}

function tripEnds(route: Route, stopKg: number[]): number[] {
  const ends: number[] = []
  let load = 0
  for (let k = 0; k < stopKg.length && ends.length < route.tripLoadsKg.length; k++) {
    load += stopKg[k]
    if (load >= route.tripLoadsKg[ends.length] - 0.5) {
      ends.push(k)
      load = 0
    }
  }
  return ends
}

function longestDwell(route: Route, from: number, to: number): [number, number] {
  let best: [number, number] = [from, from]
  for (let i = 0; i + 1 < route.times.length; i++) {
    const [start, end] = [route.times[i], route.times[i + 1]]
    if (start < from || end > to) continue
    const [lon, lat] = route.path[i]
    const [nextLon, nextLat] = route.path[i + 1]
    if (lon === nextLon && lat === nextLat && end - start > best[1] - best[0]) best = [start, end]
  }
  return best
}

export function loadAt(truck: Truck, seconds: number): number {
  let low = 0
  let high = truck.changeSeconds.length
  while (low < high) {
    const mid = (low + high) >>> 1
    if (truck.changeSeconds[mid] <= seconds) low = mid + 1
    else high = mid
  }
  return low === 0 ? 0 : truck.loadKg[low - 1]
}

export function stateAt(truck: Truck, seconds: number): TruckState {
  if (seconds >= truck.brokenAt) return 'broken'
  if (seconds >= truck.endSeconds) return 'done'
  return truck.unloads.some(([from, to]) => seconds >= from && seconds < to) ? 'unloading' : 'collecting'
}
