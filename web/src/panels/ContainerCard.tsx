import { useEffect } from 'react'
import { STREAM_NAMES, type Container } from '../demo/plan'
import { overflowPickup, type OverflowEvent } from '../operations/live'
import { fillState } from '../operations/telemetry'
import './ContainerCard.css'

interface Props {
  container: Container
  /** Lectura del sensor simulado, en porcentaje; sin API no hay sensores. */
  fill: number | undefined
  liters: number | undefined
  overflow: OverflowEvent | undefined
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
        {container.route} llega a las {clock(container.visitSeconds)} · {container.kg} kg
      </p>
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
