import { useEffect, useState } from 'react'
import { clock, secondsOfDay } from '../demo/plan'
import { decimal, tonnes } from '../format'
import type { Area, Locality, SelectionPlan, Shift } from './city'
import '../panels/Instrument.css'
import '../panels/PlanPanel.css'
import './CityPanel.css'

interface Props {
  localities: Locality[]
  selected: Set<number>
  onToggle: (locality: number) => void
  onSelect: (localities: number[]) => void
  hovered: number | null
  onHover: (locality: number | null) => void
  /** El último plan; puede ser de otra selección si se cambió después. */
  result: SelectionPlan | null
  /** Desde cuándo se está planificando, en `performance.now()`; null si no. */
  planningSince: number | null
  error: string | null
  onPlan: () => void
}

const sameSelection = (result: SelectionPlan | null, selected: Set<number>) =>
  result !== null && result.localities.length === selected.size && result.localities.every((n) => selected.has(n))

/**
 * Elegir localidades y ver cada ASE con su flota. La lista va agrupada por ASE porque es la ASE la que planifica
 * (ADR 0012): elegir Kennedy es pedirle el plan a Ciudad Limpia.
 */
export function CityPanel(props: Props) {
  const { localities, selected, result, planningSince } = props
  const current = sameSelection(result, selected) ? result : null
  const areas = [...new Set(localities.map((locality) => locality.area))].sort((a, b) => a - b)
  const urban = localities.filter((locality) => !locality.rural).map((locality) => locality.number)

  return (
    <aside className="plan panel city" aria-label="Plan por localidades">
      <div className="city-head">
        <span className="label">Localidades</span>
        <span className="num city-count">
          {selected.size}
          <span className="unit">de {localities.length}</span>
        </span>
        <button className="control" onClick={() => props.onSelect(urban)} title="Las 19 urbanas; Sumapaz es rural">
          Urbanas
        </button>
        <button className="control" onClick={() => props.onSelect([])} disabled={selected.size === 0}>
          Ninguna
        </button>
      </div>

      <div className="plan-body">
        {areas.map((number) => (
          <AreaSection
            {...props}
            key={number}
            number={number}
            localities={localities.filter((locality) => locality.area === number)}
            planned={current?.areas.find((area) => area.number === number)}
          />
        ))}
      </div>

      <div className="city-foot">
        {planningSince !== null ? (
          <Elapsed since={planningSince} />
        ) : current ? (
          <span className="num city-status">
            Calculado en {decimal(current.planMs / 1000, 1)} s · semilla 7
          </span>
        ) : (
          <button className="control city-plan" onClick={props.onPlan} disabled={selected.size === 0}>
            {selected.size === 0
              ? 'Elige localidades en el mapa'
              : `Planificar ${selected.size === 1 ? '1 localidad' : `${selected.size} localidades`}`}
          </button>
        )}
        {props.error && (
          <span className="city-error" role="alert">
            {props.error}
          </span>
        )}
      </div>
    </aside>
  )
}

/** El servidor no informa avance: lo honesto es contar el tiempo, no inventar un porcentaje. */
function Elapsed({ since }: { since: number }) {
  const [now, setNow] = useState(() => performance.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(performance.now()), 100)
    return () => clearInterval(timer)
  }, [])
  return (
    <span className="num city-status" role="status">
      Planificando · {decimal((now - since) / 1000, 1)} s
    </span>
  )
}

function AreaSection({
  number,
  localities,
  planned,
  selected,
  onToggle,
  hovered,
  onHover,
}: Props & { number: number; localities: Locality[]; planned: Area | undefined }) {
  return (
    <section className="stream city-area">
      <h2 className="city-area-name">
        <span className="label">ASE {number}</span>
        <span className="city-operator">{localities[0].operator}</span>
      </h2>
      <ul className="city-localities">
        {localities.map((locality) => (
          <li key={locality.number}>
            <button
              className={`city-locality${hovered === locality.number ? ' is-hovered' : ''}`}
              aria-pressed={selected.has(locality.number)}
              onClick={() => onToggle(locality.number)}
              onMouseEnter={() => onHover(locality.number)}
              onMouseLeave={() => onHover(null)}
              onFocus={() => onHover(locality.number)}
              onBlur={() => onHover(null)}
            >
              <span className="city-check" aria-hidden="true" />
              <span className="num city-number">{locality.number}</span>
              {locality.name}
              {locality.rural && <span className="unit">rural</span>}
            </button>
          </li>
        ))}
      </ul>
      {planned && <AreaPlan area={planned} />}
    </section>
  )
}

function AreaPlan({ area }: { area: Area }) {
  const { fleet } = area
  const beyond = fleet.declared !== null && fleet.trucks > fleet.declared
  return (
    <div className="city-result">
      <div className="city-fleet">
        <Trucks trucks={fleet.trucks} declared={fleet.declared} />
        <span className="num">
          {fleet.trucks}
          <span className="unit">{fleet.trucks === 1 ? 'camión' : 'camiones'}</span>
        </span>
        <span className={`city-declared${beyond ? ' is-beyond' : ''}`}>
          {fleet.declared === null ? 'no declara su flota' : `declara ${fleet.declared}`}
        </span>
      </div>
      {fleet.lowerBound < fleet.trucks && (
        <p className="city-note num">Ningún reparto de estos viajes usa menos de {fleet.lowerBound}</p>
      )}
      <dl className="spec">
        <div className="spec-row">
          <dt>Recogen sus contenedores</dt>
          <dd className="num">
            {tonnes(area.kgPerDay)}
            <span className="unit">t/día</span>
          </dd>
        </div>
        <div className="spec-row">
          <dt>Contenedores</dt>
          <dd>{area.shifts.some((shift) => shift.plan.meta.simulated) ? 'simulados' : 'del operador'}</dd>
        </div>
        <div className="spec-row">
          <dt>De las toneladas de la ASE</dt>
          <dd className="num">
            {area.sideLoadingShare === null ? (
              <span className="unit" title="El operador no publica sus toneladas">
                sin dato
              </span>
            ) : (
              <>
                {decimal(area.sideLoadingShare * 100, 1)}
                <span className="unit">%</span>
              </>
            )}
          </dd>
        </div>
      </dl>
      <ul className="city-shifts">
        {area.shifts.map((shift) => (
          <ShiftRow key={shift.name} shift={shift} />
        ))}
        {area.skipped.map((name) => (
          <li key={name} className="city-shift is-skipped">
            <span className="city-shift-name">{shiftName(name)}</span>
            <span>sin contenedores en la selección</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * Una celda por camión del plan; las que pasan de la flota declarada van en advertencia, y las declaradas que el plan
 * no usa quedan vacías. Se lee de un vistazo si al operador le alcanza.
 */
function Trucks({ trucks, declared }: { trucks: number; declared: number | null }) {
  const cells = Math.max(trucks, declared ?? 0)
  return (
    <span className="city-trucks" aria-hidden="true">
      {Array.from({ length: cells }, (_, i) => (
        <span
          key={i}
          className={i >= trucks ? 'is-idle' : declared !== null && i >= declared ? 'is-beyond' : undefined}
        />
      ))}
    </span>
  )
}

const shiftName = (name: string) => (name === 'Dia' ? 'Día' : name)

function ShiftRow({ shift }: { shift: Shift }) {
  const { plan } = shift
  const start = secondsOfDay(shift.start)
  const count = (id: string) => plan.compliance.find((check) => check.id === id)?.count ?? 0
  return (
    <li className="city-shift">
      <span className="city-shift-name">{shiftName(shift.name)}</span>
      <span className="num">
        {clock(start)}–{clock(start + shift.seconds)}
      </span>
      <span className="num city-shift-count">{decimal(plan.containers.length)}</span>
      <span className="num city-rules" title="R02: de noche, con medidas contra el ruido · R07: sin compactar">
        R02 {count('R02')} · R07 {count('R07')}
      </span>
    </li>
  )
}

interface InstrumentProps {
  result: SelectionPlan | null
  selected: number
}

/**
 * La lectura principal de la ciudad son los camiones: es lo que la flota de cada operador tiene que poner en la calle.
 * No va en el acento, porque no es algo que esté pasando ahora.
 */
export function CityInstrument({ result, selected }: InstrumentProps) {
  const trucks = result?.areas.reduce((sum, area) => sum + area.fleet.trucks, 0)
  const containers = result?.areas.reduce(
    (sum, area) => sum + area.shifts.reduce((n, shift) => n + shift.plan.containers.length, 0),
    0,
  )
  const kgPerDay = result?.areas.reduce((sum, area) => sum + area.kgPerDay, 0)
  return (
    <header className="instrument panel">
      <div className="instrument-head">
        <strong className="brand">Rutea</strong>
        <span className="label">Bogotá</span>
        <span
          className="stamp"
          title="Calles reales (OpenStreetMap). Contenedores del operador en las ASE 1, 2 y 4; simulados con la cantidad oficial en las ASE 3 y 5. Carga por capacidad, sin dato publicado."
        >
          Real y simulado
        </span>
      </div>

      <div className="instrument-reading">
        <span className="num instrument-clock city-reading" aria-label="Camiones de cargue lateral">
          {trucks ?? '--'}
        </span>
        <span className="instrument-shift">
          <span className="label">Camiones</span>
          <span className="num">cargue lateral</span>
        </span>
      </div>

      <dl className="instrument-figures">
        <div>
          <dt className="label">Localidades</dt>
          <dd className="num">
            <strong>{result?.localities.length ?? selected}</strong>
          </dd>
        </div>
        <div>
          <dt className="label">Contenedores</dt>
          <dd className="num">
            <strong>{containers === undefined ? '--' : decimal(containers)}</strong>
          </dd>
        </div>
        <div>
          <dt className="label">t/día</dt>
          <dd className="num">
            <strong>{kgPerDay === undefined ? '--' : tonnes(kgPerDay)}</strong>
          </dd>
        </div>
      </dl>
    </header>
  )
}
