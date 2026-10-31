import { decimal, km, tonnes } from '../format'
import { PlanningReading } from '../city/CityPanel'
import type { Collected } from '../operations/progress'
import './Instrument.css'

interface Props {
  zone: string
  /** Qué es real y qué simulado, de meta.network. */
  provenance: string | null
  time: string | null
  shift: string | null
  done: Collected | null
  totalContainers: number
  totalKg: number
  /** Metros de calle del puerta a puerta; 0 si el plan no lo tiene. */
  totalMeters: number
  /** Vehículos que transmiten y no han terminado; sin API no se sabe. */
  onStreet?: number
  /** performance.now() al pedir una operación que el servidor todavía arma. */
  planningSince: number | null
}

/**
 * El instrumento central: la hora del turno y cuánto va recogido. Es lo primero que se lee, como el tacómetro de un
 * tablero; el resto del plan queda en la ficha de abajo. La escala va por toneladas: con puerta a puerta, los
 * contenedores son la menor parte de lo que se recoge.
 */
export function Instrument(props: Props) {
  const { zone, provenance, time, shift, done, totalContainers, totalKg, totalMeters, onStreet, planningSince } = props
  const share = done && totalKg > 0 ? done.kg / totalKg : 0
  return (
    <header className="instrument panel">
      <div className="instrument-head">
        <strong className="brand">Rutea</strong>
        <span className="label">{zone} · Bogotá</span>
        <span className="stamp" title={provenance ?? undefined}>
          Simulado
        </span>
      </div>

      {planningSince !== null ? (
        <PlanningReading since={planningSince} label="Armando la operación" />
      ) : (
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
      )}

      <div
        className="instrument-scale"
        role="meter"
        aria-label="Toneladas recogidas"
        aria-valuemin={0}
        aria-valuemax={totalKg}
        aria-valuenow={done?.kg ?? 0}
      >
        <span className="instrument-fill" style={{ width: `${share * 100}%` }} />
      </div>

      <dl className="instrument-figures">
        <div>
          <dt className="label">Toneladas</dt>
          <dd className="num">
            {done ? (
              <>
                <strong>{tonnes(done.kg)}</strong>
                <span className="of">/{tonnes(totalKg)}</span>
              </>
            ) : (
              <strong>--</strong>
            )}
          </dd>
        </div>
        {totalContainers > 0 && (
          <div>
            <dt className="label">Contenedores</dt>
            <dd className="num">
              <strong>{decimal(done?.containers ?? 0)}</strong>
              <span className="of">/{decimal(totalContainers)}</span>
            </dd>
          </div>
        )}
        {totalMeters > 0 && (
          <div>
            <dt className="label">Calles km</dt>
            <dd className="num">
              <strong>{km(done?.meters ?? 0)}</strong>
              <span className="of">/{km(totalMeters)}</span>
            </dd>
          </div>
        )}
        {onStreet !== undefined && (
          <div>
            <dt className="label">En ruta</dt>
            <dd className="num">
              <strong>{onStreet}</strong>
            </dd>
          </div>
        )}
      </dl>
    </header>
  )
}
