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
            <Kpi value={(totalSeconds / 3600).toFixed(1)} unit="h" label="Horas de camión" />
            <Kpi value={tonnes(totalKg)} unit="t" label="Recolectado" />
            <Kpi value={tonnes(co2Kg)} unit="t" label="CO₂ estimado" />
            <Kpi value={String(plan.containers.length)} label="Contenedores" />
            <Kpi value={String(plan.routes.length)} unit={`· ${trips} viajes`} label="Camiones" />
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
                  <span className={`num rule-id ${check.id === 'R03' && check.count === 0 ? 'ok' : 'warn'}`}>
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
            calles reales de OpenStreetMap. Cada compactador de <span className="num">8 t</span> sale de la base de
            Fontibón, hace uno o varios viajes con su descarga en Doña Juana dentro de un turno de{' '}
            <span className="num">{plan.meta.shiftSeconds / 3600} h</span> y vuelve a la base; se usa el menor
            número de camiones. Los contenedores y sus cargas son simulados con cifras oficiales. Las velocidades,
            la parada de <span className="num">{plan.meta.serviceSeconds} s</span> por contenedor y la descarga de{' '}
            <span className="num">{plan.meta.unloadSeconds / 60} min</span> (extremo bajo del rango de 40 a 90 min
            publicado en prensa) son supuestas; con descargas más largas hacen falta más camiones. El CO₂ usa el factor del IPCC para el diésel y un consumo
            supuesto de <span className="num">{plan.meta.litersPerKm} L/km</span>.
          </p>
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
