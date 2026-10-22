import { decimal, tonnes } from '../format'
import './Instrument.css'

interface Props {
  zone: string
  /** La hora del reloj, ya formateada; sin plan todavía no hay hora. */
  time: string | null
  shift: string | null
  done: { containers: number; kg: number } | null
  totalContainers: number
  totalKg: number
  /** Vehículos que transmiten y no han terminado; sin API no se sabe. */
  onStreet?: number
}

/**
 * El instrumento central: la hora del turno y cuánto va recogido. Es lo primero que se lee, como el tacómetro de un
 * tablero; el resto del plan queda en la ficha de abajo.
 */
export function Instrument({ zone, time, shift, done, totalContainers, totalKg, onStreet }: Props) {
  const share = done && totalContainers > 0 ? done.containers / totalContainers : 0
  return (
    <header className="instrument panel">
      <div className="instrument-head">
        <strong className="brand">Rutea</strong>
        <span className="label">{zone} · Bogotá</span>
        <span className="stamp" title="Las calles son reales (OpenStreetMap); los contenedores y sus cargas, no">
          Simulado
        </span>
      </div>

      <div className="instrument-reading">
        <span className="num instrument-clock" aria-label="Hora simulada">
          {time ?? '--:--'}
        </span>
        {shift && (
          <span className="instrument-shift">
            <span className="label">Turno</span>
            <span className="num">{shift}</span>
          </span>
        )}
      </div>

      <div
        className="instrument-scale"
        role="meter"
        aria-label="Contenedores recogidos"
        aria-valuemin={0}
        aria-valuemax={totalContainers}
        aria-valuenow={done?.containers ?? 0}
      >
        <span className="instrument-fill" style={{ width: `${share * 100}%` }} />
      </div>

      <dl className="instrument-figures">
        <div>
          <dt className="label">Recogidos</dt>
          <dd className="num">
            <strong>{decimal(done?.containers ?? 0)}</strong>
            <span className="of">/{decimal(totalContainers)}</span>
          </dd>
        </div>
        <div>
          <dt className="label">Toneladas</dt>
          <dd className="num">
            <strong>{tonnes(done?.kg ?? 0)}</strong>
            <span className="of">/{tonnes(totalKg)}</span>
          </dd>
        </div>
        {onStreet !== undefined && (
          <div>
            <dt className="label">En la calle</dt>
            <dd className="num">
              <strong>{onStreet}</strong>
            </dd>
          </div>
        )}
      </dl>
    </header>
  )
}
