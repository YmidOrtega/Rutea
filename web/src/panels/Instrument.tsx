import { decimal, km, tonnes } from '../format'
import { PlanningReading } from '../city/CityPanel'
import type { Collected } from '../operations/progress'
import { loadAt, stateAt, type Truck, type TruckState } from '../operations/fleet'
import { isReserveId } from '../operations/live'
import './Instrument.css'

interface Props {
  zone: string
  provenance: string | null
  time: string | null
  shift: string | null
  done: Collected | null
  totalContainers: number
  totalKg: number
  totalMeters: number
  trucks: Truck[]
  seconds: number
  planningSince: number | null
}

/**
 * El instrumento central: la hora del turno y cuánto va recogido. Es lo primero que se lee, como el tacómetro de un
 * tablero; el resto del plan queda en la ficha de abajo.
 */
export function Instrument(props: Props) {
  const { zone, provenance, time, shift, done, totalContainers, totalKg, totalMeters, trucks, seconds, planningSince } = props
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
      </dl>

      {trucks.length > 0 && <Fleet trucks={trucks} seconds={seconds} />}
    </header>
  )
}

const STATE_NAMES: Record<TruckState, string> = {
  collecting: 'en ruta',
  unloading: 'descargando',
  broken: 'averiado',
  done: 'terminó',
}

function Fleet({ trucks, seconds }: { trucks: Truck[]; seconds: number }) {
  const states = trucks.map((truck) => stateAt(truck, seconds))
  const onStreet = states.filter((state) => state === 'collecting' || state === 'unloading').length
  return (
    <div className="fleet">
      <div className="fleet-head">
        <span className="label">Flota</span>
        <span className="num">
          <strong>{onStreet}</strong>
          <span className="of"> en ruta de {trucks.length}</span>
        </span>
      </div>
      <ul className="fleet-bars" aria-label="Carga de cada compactador">
        {trucks.map((truck, i) => {
          const load = loadAt(truck, seconds)
          const state = states[i]
          return (
            <li
              key={truck.id}
              className={`fleet-bar is-${state}${isReserveId(truck.id) ? ' is-reserve' : ''}`}
              title={`${truck.id} · ${tonnes(load)} de ${tonnes(truck.capacityKg)} t · ${STATE_NAMES[state]}`}
            >
              <span className="fleet-fill" style={{ height: `${Math.min(1, load / truck.capacityKg) * 100}%` }} />
            </li>
          )
        })}
      </ul>
    </div>
  )
}
