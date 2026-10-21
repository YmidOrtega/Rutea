import { useState } from 'react'
import { STREAM_NAMES, type DemoPlan, type WasteStream } from '../demo/plan'
import './PlanPanel.css'

interface Props {
  plan: DemoPlan
  visible: Set<string>
  onToggle: (routeId: string) => void
}

const STREAMS: WasteStream[] = ['NON_RECYCLABLE', 'RECYCLABLE', 'ORGANIC']

const km = (meters: number) => (meters / 1000).toFixed(1)
const hours = (seconds: number) => {
  const minutes = Math.round(seconds / 60)
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`
}
const tonnes = (kg: number) => (kg / 1000).toFixed(1)

export function PlanPanel({ plan, visible, onToggle }: Props) {
  const [open, setOpen] = useState(() => !window.matchMedia('(max-width: 640px)').matches)
  const totalMeters = plan.routes.reduce((sum, route) => sum + route.distanceMeters, 0)
  const totalKg = plan.routes.reduce((sum, route) => sum + route.loadKg, 0)
  const totalSeconds = plan.routes.reduce((sum, route) => sum + route.durationSeconds, 0)
  const co2Kg = (totalMeters / 1000) * plan.meta.litersPerKm * plan.meta.kgCo2PerLiter
  const trips = plan.routes.reduce((sum, route) => sum + route.tripLoadsKg.length, 0)

  return (
    <aside className="plan panel" aria-label="Plan de recolección">
      <button className="plan-toggle" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="label">Plan simulado</span>
        <span className="plan-chevron" aria-hidden="true">{open ? '−' : '+'}</span>
      </button>

      {open && (
        <div className="plan-body">
          <dl className="kpis">
            <Kpi value={km(totalMeters)} unit="km" label="Recorrido" />
            <Kpi value={(totalSeconds / 3600).toFixed(1)} unit="h" label="Horas de vehículo" />
            <Kpi value={tonnes(totalKg)} unit="t" label="Recolectado" />
            <Kpi value={tonnes(co2Kg)} unit="t" label="CO₂ estimado" />
            <Kpi value={String(plan.containers.length)} label="Contenedores" />
            <Kpi value={String(plan.routes.length)} unit={`· ${trips} viajes`} label="Vehículos" />
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
                      </li>
                    )
                  })}
                </ul>
              </section>
            )
          })}

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

          <p className="note">
            Viajes del solver <span className="num">{plan.meta.solver}</span> (semilla{' '}
            <span className="num">{plan.meta.seed}</span>,{' '}
            <span className="num">{plan.meta.iterations.toLocaleString('es-CO')}</span> iteraciones) sobre las
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

function Kpi({ value, unit, label }: { value: string; unit?: string; label: string }) {
  return (
    <div className="kpi">
      <dt className="label">{label}</dt>
      <dd className="num kpi-value">
        {value}
        {unit && <span className="unit">{unit}</span>}
      </dd>
    </div>
  )
}
