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
            <Kpi value={tonnes(totalKg)} unit="t" label="Recolectado" />
            <Kpi value={String(plan.containers.length)} label="Contenedores" />
            <Kpi value={String(plan.routes.length)} label="Viajes" />
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
                    const load = route.loadKg / route.capacityKg
                    return (
                      <li key={route.id}>
                        <button
                          className="route"
                          aria-pressed={visible.has(route.id)}
                          title={visible.has(route.id) ? 'Ocultar ruta' : 'Mostrar ruta'}
                          onClick={() => onToggle(route.id)}
                        >
                          <span className="num route-id">{route.id}</span>
                          <span
                            className="load"
                            role="meter"
                            aria-label={`Carga de ${route.id}`}
                            aria-valuemin={0}
                            aria-valuemax={route.capacityKg}
                            aria-valuenow={route.loadKg}
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
                          <span className="num route-stat" title="Duración del viaje, sin tiempo de servicio">
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

          <p className="note">
            Viajes del solver <span className="num">{plan.meta.solver}</span> (semilla{' '}
            <span className="num">{plan.meta.seed}</span>,{' '}
            <span className="num">{plan.meta.iterations.toLocaleString('es-CO')}</span> iteraciones) sobre las
            calles reales de OpenStreetMap. Cada viaje sale de la portería de Doña Juana, recoge hasta llenar un
            compactador de <span className="num">8 t</span> y vuelve a descargar. Los contenedores y sus cargas
            son simulados con cifras oficiales; las velocidades son supuestas y aún no hay tiempo de servicio.
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
