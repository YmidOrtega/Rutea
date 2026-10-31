import { useState } from 'react'
import { STREAM_NAMES, type DemoPlan, type WasteStream } from '../demo/plan'
import { scheduleCsv, windows } from '../demo/schedule'
import {
  overflowPickup,
  roadClassName,
  type BreakdownEvent,
  type ClosureEvent,
  type OperationEvent,
  type OverflowEvent,
} from '../operations/live'
import { decimal, km } from '../format'
import './PlanPanel.css'

interface Props {
  plan: DemoPlan
  visible: Set<string>
  onToggle: (routeId: string) => void
  /** Con la API detrás, cada compactador puede averiarse a la hora del reloj. */
  live: boolean
  events: OperationEvent[]
  pending: string | null
  eventError: string | null
  onBreakdown: (truck: string) => void
  closing: boolean
  onClosing: (closing: boolean) => void
  clock: (secondsFromShiftStart: number) => string
  /** La UPZ elegida en los horarios de paso: su borde se marca en el mapa. */
  sector: string | null
  onSector: (ref: string | null) => void
}

const STREAMS: WasteStream[] = ['NON_RECYCLABLE', 'RECYCLABLE', 'ORGANIC']

const hours = (seconds: number) => {
  const minutes = Math.round(seconds / 60)
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`
}
const capitalized = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

export function PlanPanel({
  plan,
  visible,
  onToggle,
  live,
  events,
  pending,
  eventError,
  onBreakdown,
  closing,
  onClosing,
  clock,
  sector,
  onSector,
}: Props) {
  const broken = new Set(events.flatMap((event) => (event.type === 'breakdown' ? [event.truck] : [])))
  const [open, setOpen] = useState(false)
  const totalMeters = plan.routes.reduce((sum, route) => sum + route.distanceMeters, 0)
  const totalSeconds = plan.routes.reduce((sum, route) => sum + route.durationSeconds, 0)
  const co2Kg = (totalMeters / 1000) * plan.meta.litersPerKm * plan.meta.kgCo2PerLiter
  const trips = plan.routes.reduce((sum, route) => sum + route.tripLoadsKg.length, 0)

  return (
    <aside className="plan panel" aria-label="Plan de recolección">
      <button className="plan-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="label">Ficha del plan</span>
        <span className="plan-chevron" aria-hidden="true">{open ? '−' : '+'}</span>
      </button>

      {open && (
        <div className="plan-body">
          <dl className="spec">
            <Spec label="Recorrido" value={km(totalMeters)} unit="km" />
            <Spec label="Horas de vehículo" value={decimal(totalSeconds / 3600, 1)} unit="h" />
            <Spec label="CO₂ estimado" value={decimal(co2Kg / 1000, 1)} unit="t" />
            <Spec label="Vehículos" value={decimal(plan.routes.length)} unit={`· ${trips} viajes`} />
            <Spec label="Contenedores" value={decimal(plan.containers.length)} />
          </dl>

          {STREAMS.map((stream) => {
            const routes = plan.routes.filter((route) => route.stream === stream)
            if (routes.length === 0) return null
            return (
              <section key={stream} className="stream">
                <h2 className="label stream-name">
                  <span className={`swatch swatch-${stream}`} aria-hidden="true" />
                  {STREAM_NAMES[stream]}
                </h2>
                <ul className="routes">
                  {routes.map((route) => {
                    // Carga promedio por viaje: cada viaje llena el compactador por separado.
                    const load = route.loadKg / (route.capacityKg * route.tripLoadsKg.length)
                    return (
                      <li key={route.id}>
                        <button
                          className="route"
                          aria-pressed={visible.has(route.id)}
                          title={visible.has(route.id) ? 'Ocultar ruta' : 'Mostrar ruta'}
                          onClick={() => onToggle(route.id)}
                        >
                          <span className="num route-id">
                            {route.id}
                            {route.tripLoadsKg.length > 1 && (
                              <span className="unit" title={`${route.tripLoadsKg.length} viajes en el turno`}>
                                ×{route.tripLoadsKg.length}
                              </span>
                            )}
                          </span>
                          <span
                            className="load"
                            role="meter"
                            aria-label={`Carga promedio por viaje de ${route.id}`}
                            aria-valuemin={0}
                            aria-valuemax={route.capacityKg}
                            aria-valuenow={Math.round(route.loadKg / route.tripLoadsKg.length)}
                          >
                            <span className={`load-bar swatch-${stream}`} style={{ width: `${load * 100}%` }} />
                          </span>
                          <span className="num route-stat">
                            {Math.round(load * 100)}
                            <span className="unit">%</span>
                          </span>
                          <span className="num route-stat">
                            {km(route.distanceMeters)}
                            <span className="unit">km</span>
                          </span>
                          <span className="num route-stat" title="Duración del turno, con paradas y descargas">
                            {hours(route.durationSeconds)}
                            <span className="unit">h</span>
                          </span>
                        </button>
                        {live && route.id.startsWith('C-') && (
                          <button
                            className={`breakdown${broken.has(route.id) ? ' is-broken' : ''}`}
                            disabled={pending !== null || broken.has(route.id)}
                            onClick={() => onBreakdown(route.id)}
                            aria-label={`Simular avería de ${route.id} a la hora del reloj`}
                            title={broken.has(route.id) ? `${route.id} ya está averiado` : 'Simular avería ahora'}
                          >
                            {pending === route.id ? '…' : <HazardIcon />}
                          </button>
                        )}
                      </li>
                    )
                  })}
                </ul>
              </section>
            )
          })}

          {live && (
            <section className="road-closure">
              <button
                className="road-closure-toggle"
                aria-pressed={closing}
                disabled={pending !== null}
                onClick={() => onClosing(!closing)}
              >
                {pending === 'closure' ? 'Cerrando…' : closing ? 'Cancelar cierre' : 'Cerrar una vía'}
              </button>
              {closing && <p className="road-closure-hint">Haz clic en una calle del mapa. Esc cancela.</p>}
            </section>
          )}

          {(events.length > 0 || eventError) && (
            <section className="events" aria-label="Eventos" aria-live="polite">
              <h2 className="label">Eventos</h2>
              {eventError && <p className="event-error">{eventError}</p>}
              <ul>
                {events.map((event) =>
                  event.type === 'breakdown' ? (
                    <Breakdown key={event.seq} event={event} clock={clock} />
                  ) : event.type === 'closure' ? (
                    <Closure key={event.seq} event={event} clock={clock} />
                  ) : (
                    <Overflow key={event.seq} event={event} clock={clock} />
                  ),
                )}
              </ul>
            </section>
          )}

          <section className="compliance" aria-label="Normativa">
            <h2 className="label">Normativa</h2>
            <ul>
              {plan.compliance.map((check) => (
                <li key={check.id} title={check.citation}>
                  <span className={`num rule-id rule-${check.status}`}>
                    {check.id}
                  </span>
                  <span>{check.summary}.</span>
                </li>
              ))}
            </ul>
          </section>

          {plan.sectors.length > 0 && (
            <details className="schedule">
              <summary className="label">Horarios de paso por UPZ · R04</summary>
              <p className="schedule-note" title={plan.compliance.find((check) => check.id === 'R04')?.citation}>
                Lo que se publicaría: de la primera a la última llegada planeada de cada corriente, en cuartos de
                hora hacia afuera. Elige una UPZ para verla en el mapa.
              </p>
              <ul>
                {plan.sectors.map((s) => {
                  const content = (
                    <>
                      <span className="sector-name">
                        {s.name}
                        {s.ref !== 'rural' && <span className="num unit">UPZ {s.ref}</span>}
                      </span>
                      {[...windows(s)]
                        .sort(([a], [b]) => STREAMS.indexOf(a) - STREAMS.indexOf(b))
                        .map(([stream, list]) => (
                          <span key={stream} className="sector-stream" title={STREAM_NAMES[stream]}>
                            <span className={`swatch swatch-${stream}`} aria-label={STREAM_NAMES[stream]} />
                            <span className="num">
                              {list.map(([from, to]) => `${clock(from)}–${clock(to)}`).join(' · ')}
                            </span>
                          </span>
                        ))}
                    </>
                  )
                  return (
                    <li key={s.ref}>
                      {s.outline.length > 0 ? (
                        <button
                          className="sector"
                          aria-pressed={sector === s.ref}
                          onClick={() => onSector(sector === s.ref ? null : s.ref)}
                        >
                          {content}
                        </button>
                      ) : (
                        <div className="sector" title="El suelo rural no se divide en UPZ: no tiene borde que marcar">
                          {content}
                        </div>
                      )}
                    </li>
                  )
                })}
              </ul>
              <button className="schedule-download" onClick={() => download(scheduleCsv(plan, clock))}>
                Descargar por vehículo (CSV)
              </button>
            </details>
          )}

          <p className="note">
            Viajes del solver <span className="num">{plan.meta.solver}</span> (semilla{' '}
            <span className="num">{plan.meta.seed}</span>,{' '}
            <span className="num">{decimal(plan.meta.iterations)}</span> iteraciones) sobre las
            calles reales de OpenStreetMap, con el menor número de vehículos en turnos de{' '}
            <span className="num">{plan.meta.shiftSeconds / 3600} h</span>. Los compactadores de{' '}
            <span className="num">8 t</span> salen de la base de Fontibón y descargan en Doña Juana en{' '}
            <span className="num">{plan.meta.unloadSeconds / 60} min</span>, el extremo bajo del rango publicado en
            prensa; con descargas más largas hacen falta más. Los aprovechables los recoge una organización de
            recicladores hasta la ECA. Supuestos:
          </p>
          <ul className="assumptions">
            {plan.meta.assumptions.map((assumption) => (
              <li key={assumption}>{assumption}.</li>
            ))}
          </ul>
        </div>
      )}
    </aside>
  )
}

function download(csv: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = Object.assign(document.createElement('a'), { href: url, download: 'rutea-horarios-suba.csv' })
  link.click()
  URL.revokeObjectURL(url)
}

function Breakdown({ event, clock }: { event: BreakdownEvent; clock: Props['clock'] }) {
  return (
    <li>
      <strong className="num">
        {clock(event.atSeconds)} · {event.truck} averiado
      </strong>
      <span>
        <span className="num">{event.orphans.length}</span> contenedores sin recoger. Sale{' '}
        {event.reserve.map((route) => route.id).join(', ')} a las{' '}
        <span className="num">{clock(event.dispatchSeconds)}</span> y llega al primero a las{' '}
        <span className="num">{clock(event.firstPickupSeconds)}</span>.
      </span>
      <span className={event.meetsRecoveryRule ? 'rule-OK' : 'rule-FAIL'} title={event.citation}>
        R05 {event.meetsRecoveryRule ? '✓' : '✗'} servicio restablecido en{' '}
        <span className="num">{Math.round((event.firstPickupSeconds - event.atSeconds) / 60)} min</span> (máximo 3
        h) · replanificado en <span className="num">{decimal(event.latencyMs)} ms</span>
      </span>
    </li>
  )
}

function Overflow({ event, clock }: { event: OverflowEvent; clock: Props['clock'] }) {
  const what =
    event.cause === 'reporte'
      ? 'desbordado'
      : event.cause === 'sensor'
        ? 'lleno según su sensor'
        : `sin recoger tras la ${event.cause}`
  return (
    <li>
      <strong className="num">
        {clock(event.atSeconds)} · {event.container} {what}
      </strong>
      <span className="num">{capitalized(overflowPickup(event, clock))}.</span>
      <span>
        Decidido en{' '}
        <span className="num">
          {event.latencyMs < 1 ? `${decimal(event.latencyMs * 1000)} µs` : `${decimal(event.latencyMs, 1)} ms`}
        </span>
      </span>
    </li>
  )
}

const sign = (value: number) => (value < 0 ? '−' : '+')
const signedDistance = (meters: number) =>
  Math.abs(meters) < 1000 ? `${sign(meters)}${Math.abs(meters)} m` : `${sign(meters)}${km(Math.abs(meters))} km`
const signedDuration = (seconds: number) =>
  Math.abs(seconds) < 60 ? `${sign(seconds)}${Math.abs(seconds)} s` : `${sign(seconds)}${Math.round(Math.abs(seconds) / 60)} min`

function Closure({ event, clock }: { event: ClosureEvent; clock: Props['clock'] }) {
  return (
    <li>
      <strong className="num">
        {clock(event.atSeconds)} · {capitalized(roadClassName(event.roadClass))} cerrada
      </strong>
      {event.changes.length === 0 ? (
        <span>Ningún compactador pasaba por ahí en lo que le queda de turno.</span>
      ) : (
        event.changes.map((change) => (
          <span key={change.truck} className="num">
            {change.truck} {change.rerouted ? 'iba en camino y gira' : 'se desvía'}: {signedDistance(change.extraMeters)},{' '}
            {signedDuration(change.extraSeconds)}
            {change.late && <span className="rule-FAIL"> · termina fuera de turno</span>}
          </span>
        ))
      )}
      {event.isolated.length > 0 && (
        <span className="rule-FAIL">
          <span className="num">{event.isolated.length}</span> contenedores sin acceso
          {event.dropped.length > 0 && (
            <>
              ; <span className="num">{event.dropped.length}</span> visitas pendientes salen del plan
            </>
          )}
        </span>
      )}
      <span title={event.citation}>
        R05 cambio de ruta por caso fortuito, sin el aviso de 3 días · matriz reparada con{' '}
        <span className="num">
          {event.recomputed} de {event.matrixSize}
        </span>{' '}
        filas y columnas en <span className="num">{decimal(event.latencyMs)} ms</span>
      </span>
    </li>
  )
}

function Spec({ value, unit, label }: { value: string; unit?: string; label: string }) {
  return (
    <div className="spec-row">
      <dt>{label}</dt>
      <dd className="num">
        {value}
        {unit && <span className="unit">{unit}</span>}
      </dd>
    </div>
  )
}

function HazardIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
      <path d="M6 1 11.2 10.5H.8z" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
      <path d="M5.4 4.4h1.2v3H5.4zM5.4 8.2h1.2v1.2H5.4z" fill="currentColor" />
    </svg>
  )
}
