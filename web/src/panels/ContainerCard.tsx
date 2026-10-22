import { useEffect } from 'react'
import { STREAM_NAMES, type Container } from '../demo/plan'
import { overflowPickup, type OverflowEvent } from '../operations/live'
import { fillState } from '../operations/telemetry'
import type { Visit } from '../operations/progress'
import type { Window } from '../demo/schedule'
import './ContainerCard.css'

interface Props {
  container: Container
  /** Lectura del sensor simulado, en porcentaje; sin API no hay sensores. */
  fill: number | undefined
  liters: number | undefined
  overflow: OverflowEvent | undefined
  /** La primera llegada del plan vigente; sin ella, el contenedor quedó sin visita. */
  visit: Visit | undefined
  sectorName: string | undefined
  /** El horario de paso publicado para su UPZ y su corriente (R04). */
  published: Window | undefined
  /** Hora del reloj, en segundos desde el inicio del turno: el desborde se reporta a esa hora. */
  now: number
  pending: boolean
  error: string | null
  onReport: () => void
  onClose: () => void
  clock: (secondsFromShiftStart: number) => string
}

/** El contenedor que se tocó en el mapa y lo que se puede hacer con él: reportarlo desbordado. */
export function ContainerCard({
  container,
  fill,
  liters,
  overflow,
  visit,
  sectorName,
  published,
  now,
  pending,
  error,
  onReport,
  onClose,
  clock,
}: Props) {
  useEffect(() => {
    const close = (event: KeyboardEvent) => event.key === 'Escape' && onClose()
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [onClose])

  const compactor = container.stream === 'NON_RECYCLABLE'
  // Al minuto, como se muestran las horas: 20:15 y algo no está "fuera" de una ventana que termina a las 20:15.
  const minute = visit && Math.floor(visit.seconds / 60)
  const offSchedule =
    minute === undefined || !published
      ? 0
      : minute > published[1] / 60
        ? minute - published[1] / 60
        : minute < published[0] / 60
          ? minute - published[0] / 60
          : 0
  return (
    <section className="container-card panel" aria-label={`Contenedor ${container.id}`}>
      <header>
        <strong className="num">{container.id}</strong>
        <button className="card-close" onClick={onClose} aria-label="Cerrar">
          ×
        </button>
      </header>
      <span className="label">{STREAM_NAMES[container.stream]} · simulado</span>
      <p className="num">
        {visit ? `${visit.route} llega a las ${clock(visit.seconds)}` : 'Sin visita en el plan vigente'} ·{' '}
        {container.kg} kg
      </p>
      {sectorName && published && (
        <p className="num">
          {sectorName} · horario publicado {clock(published[0])}–{clock(published[1])}
        </p>
      )}
      {offSchedule !== 0 && (
        <p className="card-overflow num">
          Hoy llega {Math.abs(offSchedule)} min {offSchedule > 0 ? 'después' : 'antes'} del horario publicado.
        </p>
      )}
      {fill !== undefined && liters !== undefined && (
        <p className={`num card-fill fill-${fillState(fill)}`}>
          {fill >= 100 ? 'Desbordado' : `Lleno al ${fill} %`} · {liters.toLocaleString('es-CO')} L · sensor simulado
        </p>
      )}
      {overflow && (
        <p className="card-overflow num">
          Desbordado a las {clock(overflow.atSeconds)}: {overflowPickup(overflow, clock)}.
        </p>
      )}
      {error && <p className="card-error">{error}</p>}
      {compactor && overflow && overflow.pickupSeconds > now ? (
        <p className="card-note">Ya está reportado: la recogida va en camino.</p>
      ) : compactor ? (
        <button className="card-report" disabled={pending} onClick={onReport} autoFocus>
          {pending ? 'Despachando…' : <>Reportar desborde a las <span className="num">{clock(now)}</span></>}
        </button>
      ) : (
        <p className="card-note">La operación en vivo cubre por ahora los compactadores, no los aprovechables.</p>
      )}
    </section>
  )
}
