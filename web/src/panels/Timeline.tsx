import { useId, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react'
import { secondsOfDay, STREAM_NAMES, type ComplianceCheck, type WasteStream } from '../demo/plan'
import type { OperationEvent } from '../operations/live'
import { collectedBy, type Progress } from '../operations/progress'
import './Timeline.css'

interface Props {
  shiftStart: string
  /** Segundos desde el inicio del turno hasta donde llega la línea. */
  end: number
  time: number
  progress: Progress
  streams: WasteStream[]
  totalContainers: number
  events: OperationEvent[]
  /** La regla R02 del plan: su franja se marca sobre la línea. */
  night: ComplianceCheck | undefined
  clock: (secondsFromShiftStart: number) => string
  /** Reproducir y velocidad: el reloj es del servidor o del navegador, según haya API. */
  children: ReactNode
  /** Sin API, la hora es local y se puede mover; con API, el reloj es el mismo para todos y no se arrastra. */
  onSeek?: (seconds: number) => void
  /** Vehículos que transmiten y no han terminado; sin API no se sabe. */
  onStreet?: number
}

const BUCKET = 15 * 60
const LANE = 18
const GAP = 3
const NIGHT_START = '21:00'
const NIGHT_HOURS = 9
const KEY_STEP = 5 * 60

const tonnes = (kg: number) => (kg / 1000).toFixed(1)
const share = (seconds: number, end: number) => `${(Math.min(Math.max(seconds, 0), end) / end) * 100}%`

export function Timeline({
  shiftStart,
  end,
  time,
  progress,
  streams,
  totalContainers,
  events,
  night,
  clock,
  children,
  onSeek,
  onStreet,
}: Props) {
  const clip = useId()
  const done = collectedBy(progress, time)
  const buckets = Math.ceil(end / BUCKET)
  const counts = new Map(streams.map((stream) => [stream, new Array<number>(buckets).fill(0)]))
  for (const visit of progress.visits) {
    const lane = counts.get(visit.stream)
    if (lane) lane[Math.min(buckets - 1, Math.floor(visit.seconds / BUCKET))]++
  }
  const highest = Math.max(1, ...[...counts.values()].flat())
  const height = streams.length * (LANE + GAP) - GAP
  const nightFrom = (secondsOfDay(NIGHT_START) - secondsOfDay(shiftStart) + 86_400) % 86_400
  const hours = Array.from({ length: Math.floor(end / 3600) + 1 }, (_, hour) => hour * 3600)

  const lanes = streams.map((stream, i) => (
    <g key={stream} className={`lane lane-${stream}`} transform={`translate(0 ${i * (LANE + GAP)})`}>
      {counts.get(stream)!.map((count, b) => {
        const h = count === 0 ? 0 : Math.max(2, (count / highest) * LANE)
        // En porcentaje y sin viewBox: un SVG estirado con preserveAspectRatio="none" sale borroso en Chrome
        return <rect key={b} x={share(b * BUCKET + BUCKET * 0.12, end)} width={share(BUCKET * 0.76, end)} y={LANE - h} height={h} />
      })}
    </g>
  ))

  const seekTo = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    onSeek?.(Math.round(((event.clientX - box.left) / box.width) * end))
  }
  const keys = (event: KeyboardEvent<HTMLDivElement>) => {
    const to = { ArrowLeft: time - KEY_STEP, ArrowRight: time + KEY_STEP, Home: 0, End: end }[event.key]
    if (to === undefined || !onSeek) return
    event.preventDefault()
    onSeek(Math.min(Math.max(to, 0), end))
  }

  return (
    <section className="timeline panel" aria-label="Línea de tiempo del turno">
      <div className="timeline-head">
        {children}
        <p className="timeline-metrics" aria-live="off">
          <span className="num">
            <strong>{done.containers}</strong> de {totalContainers}
          </span>{' '}
          contenedores ·{' '}
          <span className="num">
            <strong>{tonnes(done.kg)}</strong> de {tonnes(progress.kgBefore[progress.kgBefore.length - 1])} t
          </span>
          {onStreet !== undefined && (
            <>
              {' '}
              · <span className="num">{onStreet}</span> en la calle
            </>
          )}
        </p>
      </div>

      <div
        className={`timeline-track${onSeek ? ' seekable' : ''}`}
        {...(onSeek
          ? {
              role: 'slider',
              tabIndex: 0,
              'aria-label': 'Hora del turno',
              'aria-valuemin': 0,
              'aria-valuemax': end,
              'aria-valuenow': Math.round(time),
              'aria-valuetext': clock(time),
              onPointerDown: (event: PointerEvent<HTMLDivElement>) => {
                event.currentTarget.setPointerCapture(event.pointerId)
                seekTo(event)
              },
              onPointerMove: (event: PointerEvent<HTMLDivElement>) => event.buttons === 1 && seekTo(event),
              onKeyDown: keys,
            }
          : { title: 'El reloj es el mismo para todos: se mueve con ▶' })}
      >
        {nightFrom < end && (
          <div
            className="timeline-night"
            style={{ left: share(nightFrom, end), right: `calc(100% - ${share(nightFrom + NIGHT_HOURS * 3600, end)})` }}
            title={night ? `${night.summary}. ${night.citation}` : undefined}
          >
            <span className="num">R02 · 21:00–06:00</span>
          </div>
        )}

        <div className="timeline-events" aria-hidden="true">
          {events.map((event) => (
            <span
              key={event.seq}
              className={`timeline-event timeline-event-${event.type}`}
              style={{ left: share(event.atSeconds, end) }}
              title={`${clock(event.atSeconds)} · ${eventName(event)}`}
            />
          ))}
        </div>

        <svg
          className="timeline-lanes"
          height={height}
          role="img"
          aria-label={`Contenedores recogidos cada 15 minutos: ${streams.map((s) => STREAM_NAMES[s].toLowerCase()).join(' y ')}`}
        >
          <defs>
            <clipPath id={clip}>
              <rect width={share(time, end)} height={height} />
            </clipPath>
          </defs>
          <g className="timeline-ahead">{lanes}</g>
          <g clipPath={`url(#${clip})`}>{lanes}</g>
        </svg>

        <div className="timeline-axis num" aria-hidden="true">
          {hours.map((seconds) =>
            seconds % 7200 === 0 && seconds <= end - 1800 ? (
              <span key={seconds} style={{ left: share(seconds, end) }}>
                {clock(seconds)}
              </span>
            ) : null,
          )}
        </div>

        <div className="timeline-now" style={{ left: share(time, end) }} aria-hidden="true" />
      </div>
    </section>
  )
}

function eventName(event: OperationEvent): string {
  switch (event.type) {
    case 'breakdown':
      return `${event.truck} averiado`
    case 'overflow':
      return `${event.container} ${event.cause === 'sensor' ? 'lleno según su sensor' : 'desbordado'}`
    case 'closure':
      return 'vía cerrada'
  }
}
