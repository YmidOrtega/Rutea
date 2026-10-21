import { PathLayer, PolygonLayer, ScatterplotLayer } from '@deck.gl/layers'
import { TripsLayer } from '@deck.gl/geo-layers'
import type { Container, DemoPlan, LonLat, Route, Site, Trip } from '../demo/plan'
import { strokeColor, type Palette } from './colors'
import suba from './suba.json'

/**
 * Las rutas son líneas planas sobre el suelo y muchas comparten el corredor al relleno. Con el mapa inclinado,
 * la prueba de profundidad las hace pelear pixel a pixel (z-fighting) y el corredor se ve punteado: se dibujan
 * en el orden de las capas.
 */
const FLAT = { depthCompare: 'always', depthWriteEnabled: false } as const

/** Fuente y licencia en suba.json. */
const SUBA = suba.geometry.coordinates[0] as LonLat[]

const SURROUNDINGS: LonLat[] = [
  [-75, 4],
  [-73, 4],
  [-73, 5.6],
  [-75, 5.6],
  [-75, 4],
]

export function zoneLayers(palette: Palette) {
  return [
    new PolygonLayer<LonLat[][]>({
      id: 'zone-mask',
      data: [[SURROUNDINGS, SUBA]],
      getPolygon: (rings) => rings,
      getFillColor: [...palette.bg, 150],
      stroked: false,
    }),
    new PathLayer<LonLat[]>({
      id: 'zone-edge',
      data: [SUBA],
      getPath: (ring) => ring,
      getColor: [...palette.fgMuted, 200],
      getWidth: 1.5,
      widthUnits: 'pixels',
      jointRounded: true,
    }),
  ]
}

export function staticLayers(plan: DemoPlan, visible: Set<string>, palette: Palette) {
  const routes = plan.routes.filter((route) => visible.has(route.id))
  const containers = plan.containers.filter((container) => visible.has(container.route))
  const nonRecyclable = routes.filter((route) => route.stream === 'NON_RECYCLABLE')

  return [
    // Borde claro bajo las rutas de no aprovechables, para que su núcleo negro se lea
    new PathLayer<Route>({
      id: 'routes-edge',
      data: nonRecyclable,
      getPath: (route) => route.path,
      getColor: palette.nonRecyclableEdge,
      getWidth: 6,
      widthUnits: 'pixels',
      opacity: 0.35,
      jointRounded: true,
      capRounded: true,
      parameters: FLAT,
    }),
    new PathLayer<Route>({
      id: 'routes',
      data: routes,
      getPath: (route) => route.path,
      getColor: (route) => palette.stream[route.stream],
      getWidth: 3,
      widthUnits: 'pixels',
      opacity: 0.35,
      jointRounded: true,
      capRounded: true,
      parameters: FLAT,
    }),
    new ScatterplotLayer<Container>({
      id: 'containers',
      data: containers,
      getPosition: (container) => container.position,
      getFillColor: (container) => palette.stream[container.stream],
      // Ámbar si ahí no se puede compactar (R07): es lo único que el conductor tiene que hacer distinto.
      getLineColor: (container) => (container.noCompaction ? palette.warn : strokeColor(palette, container.stream)),
      getRadius: 14,
      radiusMinPixels: 3,
      radiusMaxPixels: 9,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 1.5,
      pickable: true,
      autoHighlight: true,
    }),
    // Relleno: punto lleno. Base: anillo. ECA: punto del color de los aprovechables, que terminan ahí.
    new ScatterplotLayer<Site>({
      id: 'sites',
      data: plan.sites,
      getPosition: (site) => site.position,
      getFillColor: (site) =>
        site.id === 'relleno' ? palette.fg : site.id === 'eca' ? palette.stream.RECYCLABLE : palette.bg,
      getLineColor: palette.fg,
      getRadius: 60,
      radiusMinPixels: 6,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 2.5,
      pickable: true,
    }),
  ]
}

/** Las rutas de reserva tras una avería llevan el acento: son lo que está pasando ahora (docs/diseno.md). */
const isReserve = (route: Route) => route.id.startsWith('R-')

/**
 * Lo que dejó una avería: los contenedores huérfanos con borde crítico y las rutas de reserva en el acento.
 * Se dibuja encima del plan.
 */
export function operationLayers(reserve: Route[], orphans: Container[], palette: Palette) {
  return [
    new PathLayer<Route>({
      id: 'reserve-routes',
      data: reserve,
      getPath: (route) => route.path,
      getColor: palette.sodium,
      getWidth: 3,
      widthUnits: 'pixels',
      opacity: 0.7,
      jointRounded: true,
      capRounded: true,
      parameters: FLAT,
    }),
    new ScatterplotLayer<Container>({
      id: 'orphans',
      data: orphans,
      getPosition: (container) => container.position,
      getFillColor: (container) => palette.stream[container.stream],
      getLineColor: palette.crit,
      getRadius: 18,
      radiusMinPixels: 4,
      radiusMaxPixels: 10,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 2,
      pickable: true,
    }),
  ]
}

export function tripsLayer(trips: Trip[], visible: Set<string>, time: number, palette: Palette) {
  return new TripsLayer<Trip>({
    id: 'trips',
    data: trips.filter((trip) => visible.has(trip.route.id)),
    getPath: (trip) => trip.route.path,
    getTimestamps: (trip) => trip.timestamps,
    getColor: (trip) => (isReserve(trip.route) ? palette.sodium : strokeColor(palette, trip.route.stream)),
    getWidth: 4,
    widthUnits: 'pixels',
    jointRounded: true,
    capRounded: true,
    fadeTrail: true,
    parameters: FLAT,
    trailLength: 900,
    currentTime: time,
  })
}
