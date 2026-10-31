import { PathLayer, PolygonLayer, ScatterplotLayer } from '@deck.gl/layers'
import { TripsLayer } from '@deck.gl/geo-layers'
import type { Container, DemoPlan, LonLat, Route, Site, Street, Trip } from '../demo/plan'
import type { CityRoute, Locality } from '../city/city'
import { isReserveId, type ClosureEvent, type OverflowEvent } from '../operations/live'
import { fillState, type VehicleTrack } from '../operations/telemetry'
import { operatorColor, strokeColor, type Palette, type RGB } from './colors'
import suba from './suba.json'

/**
 * Las rutas son líneas planas sobre el suelo y muchas comparten el corredor al relleno. Con el mapa inclinado,
 * la prueba de profundidad las hace pelear pixel a pixel (z-fighting) y el corredor se ve punteado: se dibujan
 * en el orden de las capas.
 */
const FLAT = { depthCompare: 'always', depthWriteEnabled: false } as const

/** Fuente y licencia en suba.json. Sin la API es el único borde que se conoce. */
export const SUBA = [suba.geometry.coordinates[0] as LonLat[]]

const SURROUNDINGS: LonLat[] = [
  [-75, 4],
  [-73, 4],
  [-73, 5.6],
  [-75, 5.6],
  [-75, 4],
]

export function zoneLayers(outline: LonLat[][], palette: Palette) {
  return [
    new PolygonLayer<LonLat[][]>({
      id: 'zone-mask',
      data: [[SURROUNDINGS, ...outline]],
      getPolygon: (rings) => rings,
      getFillColor: [...palette.mapBg, 170],
      stroked: false,
    }),
    new PathLayer<LonLat[]>({
      id: 'zone-edge',
      data: outline,
      getPath: (ring) => ring,
      getColor: [...palette.fgMuted, 200],
      getWidth: 1.5,
      widthUnits: 'pixels',
      jointRounded: true,
    }),
  ]
}

/**
 * El selector de localidades: las elegidas quedan bajo la luz de sodio, porque son una selección, y la que está bajo
 * el puntero marca su borde para saber qué se va a elegir. Las demás dejan ver el mapa tal cual.
 */
export function localityLayers(
  localities: Locality[],
  selected: Set<number>,
  hovered: number | null,
  palette: Palette,
) {
  const parts = localities.flatMap((locality) => locality.outline.map((ring) => ({ locality, ring })))
  const chosen = (part: { locality: Locality }) => selected.has(part.locality.number)
  return [
    new PolygonLayer<{ locality: Locality; ring: LonLat[] }>({
      id: 'localities',
      data: parts,
      getPolygon: (part) => part.ring,
      // Sin elegir, casi transparente pero no del todo: así también se puede hacer clic en ella.
      getFillColor: (part) => (chosen(part) ? [...palette.fg, 14] : [...palette.mapBg, 8]),
      stroked: false,
      pickable: true,
      updateTriggers: { getFillColor: [...selected].join(',') },
      parameters: FLAT,
    }),
    new PathLayer<{ locality: Locality; ring: LonLat[] }>({
      id: 'locality-edges',
      data: parts,
      getPath: (part) => part.ring,
      getColor: (part) =>
        part.locality.number === hovered
          ? palette.fg
          : chosen(part)
            ? [...palette.sodium, 150]
            : [...palette.fgMuted, 90],
      getWidth: (part) => (part.locality.number === hovered ? 2 : 1),
      widthUnits: 'pixels',
      jointRounded: true,
      updateTriggers: { getColor: [hovered, [...selected].join(',')], getWidth: hovered },
      parameters: FLAT,
    }),
  ]
}

/**
 * En la ciudad todas las rutas son de no aprovechables: el color de la corriente no distinguiría nada, así que cada
 * una va en el de su operador, mezclado con el fondo como las del plan en vivo.
 */
export function cityRoutesLayer(routes: CityRoute[], palette: Palette) {
  return new PathLayer<CityRoute>({
    id: 'city-routes',
    data: routes,
    getPath: (route) => route.path,
    getColor: (route) => mix(palette.mapBg, operatorColor(palette, route.area), CITY_ROUTES),
    getWidth: 2.5,
    widthUnits: 'pixels',
    jointRounded: true,
    capRounded: true,
    parameters: FLAT,
  })
}

/** Sin estelas que brillen encima, en la ciudad el plan mismo es lo que se mira: casi en su color pleno. */
const CITY_ROUTES = 0.9

/** El borde de la UPZ elegida en los horarios de paso: es una selección, va en el acento. */
export function sectorLayer(outline: LonLat[], palette: Palette) {
  return new PathLayer<LonLat[]>({
    id: 'sector',
    data: [outline],
    getPath: (ring) => ring,
    getColor: palette.sodium,
    getWidth: 2,
    widthUnits: 'pixels',
    jointRounded: true,
    parameters: FLAT,
  })
}

/**
 * El plan es el fondo: lo que ya se recorrió, la estela, es lo que brilla. Opaco y no transparente, porque una
 * docena de recorridos comparten cada avenida y con transparencia se suman hasta volverse blancos.
 */
const PLANNED = 0.22

const mix = (from: RGB, to: RGB, t: number): RGB =>
  [0, 1, 2].map((i) => Math.round(from[i] + (to[i] - from[i]) * t)) as RGB

export function staticLayers(plan: Pick<DemoPlan, 'routes' | 'sites'>, visible: Set<string>, palette: Palette) {
  const routes = plan.routes.filter((route) => visible.has(route.id))

  return [
    new PathLayer<Route>({
      id: 'routes',
      data: routes,
      getPath: (route) => route.path,
      getColor: (route) => mix(palette.mapBg, strokeColor(palette, route.stream), PLANNED),
      getWidth: 1.5,
      widthUnits: 'pixels',
      jointRounded: true,
      capRounded: true,
      parameters: FLAT,
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

/**
 * Lo que falta por recoger es apenas un punto: como la línea de tiempo, el mapa se enciende a medida que pasa el
 * turno, y Suba no arranca como una mancha de 1.612 puntos.
 */
const PENDING = 55

export function containersLayer(
  containers: Container[],
  firstVisit: Map<string, number>,
  seconds: number,
  palette: Palette,
) {
  // Al minuto: recalcular el color de cada contenedor en cada cuadro no cambia nada que se vea
  const minute = Math.floor(seconds / 60)
  const collected = (container: Container) => collectedBy(firstVisit, container, minute * 60)
  return new ScatterplotLayer<Container>({
    id: 'containers',
    data: containers,
    getPosition: (container) => container.position,
    getFillColor: (container) => [...palette.stream[container.stream], collected(container) ? 255 : PENDING],
    // Advertencia si ahí no se puede compactar (R07): es lo único que el conductor tiene que hacer distinto.
    getLineColor: (container) =>
      container.noCompaction
        ? palette.warn
        : [...strokeColor(palette, container.stream), collected(container) ? 255 : PENDING],
    getRadius: (container) => (collected(container) ? 10 : 5),
    radiusMinPixels: 1,
    radiusMaxPixels: 6,
    stroked: true,
    lineWidthUnits: 'pixels',
    getLineWidth: (container) => (collected(container) || container.noCompaction ? 1 : 0),
    pickable: true,
    autoHighlight: true,
    updateTriggers: { getFillColor: minute, getLineColor: minute, getRadius: minute, getLineWidth: minute },
  })
}

/**
 * El puerta a puerta: cada trozo de calle, más ancho que las rutas porque es lo que se recoge y no solo por donde se
 * pasa, y más todavía ya recogido. Se enciende con su primera visita, como los contenedores. Solo los no aprovechables van casa por casa.
 */
export function streetsLayer(streets: Street[], firstVisit: Map<string, number>, seconds: number, palette: Palette) {
  const minute = Math.floor(seconds / 60)
  const edge = strokeColor(palette, 'NON_RECYCLABLE')
  const collected = (street: Street) => (firstVisit.get(street.id) ?? Infinity) <= minute * 60
  return new PathLayer<Street>({
    id: 'streets',
    data: streets,
    getPath: (street) => street.path,
    getColor: (street) => mix(palette.mapBg, edge, collected(street) ? STREET_COLLECTED : STREET_PENDING),
    getWidth: (street) => (collected(street) ? 3 : 2),
    widthUnits: 'pixels',
    jointRounded: true,
    capRounded: true,
    pickable: true,
    autoHighlight: true,
    highlightColor: [...palette.fg, 255],
    updateTriggers: { getColor: minute, getWidth: minute },
    parameters: FLAT,
  })
}

const STREET_PENDING = 0.25
const STREET_COLLECTED = 0.75

const collectedBy = (firstVisit: Map<string, number>, container: Container, seconds: number) =>
  (firstVisit.get(container.id) ?? Infinity) <= seconds

/**
 * Lo que dejaron los eventos: los contenedores huérfanos con borde crítico, los desbordados con un anillo crítico
 * más amplio, el contenedor seleccionado con un anillo en el acento y las rutas de reserva en el acento. Se dibuja
 * encima del plan. Un huérfano deja de serlo cuando la reserva lo recoge, y un desborde cuando alguien pasa por él:
 * desde ahí el rojo sería una alarma que ya no pide nada.
 */
export function operationLayers(
  reserve: Route[],
  orphans: Container[],
  overflowed: Container[],
  overflows: Map<string, OverflowEvent>,
  firstVisit: Map<string, number>,
  selected: Container | null,
  closures: ClosureEvent[],
  seconds: number,
  palette: Palette,
) {
  const minute = Math.floor(seconds / 60)
  const now = minute * 60
  // Al repetir el turno desde antes, lo que todavía no pasa no se dibuja
  const happened = closures.filter((closure) => closure.atSeconds <= now)
  const pending = orphans.filter((container) => !collectedBy(firstVisit, container, now))
  const spilled = overflowed.filter((container) => {
    const overflow = overflows.get(container.id)
    return overflow !== undefined && overflow.atSeconds <= now && overflow.pickupSeconds > now
  })
  const running = (route: Route) => (route.times.at(-1) ?? 0) > now
  return [
    // Una barrera: núcleo crítico sobre un borde del fondo, para que se lea encima de cualquier ruta.
    new PathLayer<ClosureEvent>({
      id: 'closures-edge',
      data: happened,
      getPath: (closure) => closure.path,
      getColor: palette.bg,
      getWidth: 9,
      widthUnits: 'pixels',
      capRounded: true,
      parameters: FLAT,
    }),
    new PathLayer<ClosureEvent>({
      id: 'closures',
      data: happened,
      getPath: (closure) => closure.path,
      getColor: palette.crit,
      getWidth: 5,
      widthUnits: 'pixels',
      capRounded: true,
      pickable: true,
      parameters: FLAT,
    }),
    // A la escala de la localidad, una cuadra cerrada mide un par de píxeles: el anillo la hace visible.
    new ScatterplotLayer<ClosureEvent>({
      id: 'closure-marks',
      data: happened,
      getPosition: (closure) => closure.path[Math.floor(closure.path.length / 2)],
      getFillColor: palette.bg,
      getLineColor: palette.crit,
      getRadius: 30,
      radiusMinPixels: 7,
      radiusMaxPixels: 12,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 2.5,
      pickable: true,
      parameters: FLAT,
    }),
    new PathLayer<Route>({
      id: 'reserve-routes',
      data: reserve.filter((route) => (route.times[0] ?? 0) <= now),
      getPath: (route) => route.path,
      getColor: (route) => (running(route) ? [...palette.sodium, 150] : [...palette.fgMuted, 45]),
      getWidth: (route) => (running(route) ? 2 : 1),
      widthUnits: 'pixels',
      updateTriggers: { getColor: minute, getWidth: minute },
      jointRounded: true,
      capRounded: true,
      parameters: FLAT,
    }),
    new ScatterplotLayer<Container>({
      id: 'orphans',
      data: pending,
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
    new ScatterplotLayer<Container>({
      id: 'overflows',
      data: spilled,
      getPosition: (container) => container.position,
      getLineColor: palette.crit,
      getRadius: 30,
      radiusMinPixels: 6,
      radiusMaxPixels: 11,
      filled: false,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 1.5,
      pickable: true,
    }),
    new ScatterplotLayer<Container>({
      id: 'selected',
      data: selected ? [selected] : [],
      getPosition: (container) => container.position,
      getLineColor: palette.sodium,
      getRadius: 60,
      radiusMinPixels: 13,
      radiusMaxPixels: 22,
      filled: false,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 2,
    }),
  ]
}

/** Las reservas llevan el acento: son lo que está pasando ahora (docs/diseno.md). */
export function tripsLayer(trips: Trip[], visible: Set<string>, time: number, palette: Palette) {
  return new TripsLayer<Trip>({
    id: 'trips',
    data: trips.filter((trip) => visible.has(trip.id)),
    getPath: (trip) => trip.path,
    getTimestamps: (trip) => trip.timestamps,
    getColor: (trip) => (isReserveId(trip.id) ? palette.sodium : strokeColor(palette, trip.stream)),
    getWidth: 3,
    widthUnits: 'pixels',
    jointRounded: true,
    capRounded: true,
    fadeTrail: true,
    parameters: FLAT,
    trailLength: 900,
    currentTime: time,
  })
}

export interface VehicleMark {
  vehicle: VehicleTrack
  position: LonLat
}

/** Dónde reporta estar cada vehículo. El averiado lleva el borde crítico; el que terminó su turno no se dibuja. */
export function vehiclesLayer(marks: VehicleMark[], visible: Set<string>, palette: Palette) {
  return new ScatterplotLayer<VehicleMark>({
    id: 'vehicles',
    data: marks.filter((mark) => visible.has(mark.vehicle.id) && mark.vehicle.state !== 'DONE'),
    getPosition: (mark) => mark.position,
    getFillColor: (mark) =>
      isReserveId(mark.vehicle.id) ? palette.sodium : strokeColor(palette, mark.vehicle.stream),
    getLineColor: (mark) => (mark.vehicle.state === 'BROKEN' ? palette.crit : palette.bg),
    getRadius: 20,
    radiusMinPixels: 4,
    radiusMaxPixels: 8,
    stroked: true,
    lineWidthUnits: 'pixels',
    getLineWidth: (mark) => (mark.vehicle.state === 'BROKEN' ? 2.5 : 1.5),
    pickable: true,
    parameters: FLAT,
  })
}

/**
 * El llenado que reporta cada sensor, como un punto dentro del contenedor: el borde ya dice si se puede compactar
 * (R07) y el color del contenedor es el de su corriente, así que el estado va en el centro. Solo los que piden
 * atención: 806 puntos verdes no dicen nada que la ausencia de punto no diga.
 */
export function fillLayer(containers: Container[], readings: Map<string, number>, palette: Palette) {
  return new ScatterplotLayer<Container>({
    id: 'fill',
    data: containers.filter((container) => fillState(readings.get(container.id) ?? 0) !== 'ok'),
    getPosition: (container) => container.position,
    getFillColor: (container) => palette[fillState(readings.get(container.id) ?? 0)],
    getRadius: 6,
    radiusMinPixels: 1.5,
    radiusMaxPixels: 4,
    updateTriggers: { getFillColor: readings },
    parameters: FLAT,
  })
}
