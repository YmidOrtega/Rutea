import { STREAM_NAMES, type DemoPlan, type Sector, type WasteStream } from './plan'

/** Lo que ve un vecino: cuándo pasa cada corriente por su UPZ, sin importar qué vehículo. */
export type Window = [from: number, to: number]

/** Las pasadas de cada corriente, juntando las que se tocan o se cruzan. */
export function windows(sector: Sector): Map<WasteStream, Window[]> {
  const merged = new Map<WasteStream, Window[]>()
  for (const pass of [...sector.passes].sort((a, b) => a.fromSeconds - b.fromSeconds)) {
    const list = merged.get(pass.stream) ?? []
    const last = list.at(-1)
    if (last && pass.fromSeconds <= last[1]) last[1] = Math.max(last[1], pass.toSeconds)
    else list.push([pass.fromSeconds, pass.toSeconds])
    merged.set(pass.stream, list)
  }
  return merged
}

export function windowAt(sector: Sector, stream: WasteStream, seconds: number): Window | undefined {
  return windows(sector)
    .get(stream)
    ?.find(([from, to]) => from <= seconds && seconds <= to)
}

/** Una fila por pasada de vehículo, como la publicaría el prestador en su página (R04). */
export function scheduleCsv(plan: DemoPlan, clock: (seconds: number) => string): string {
  const rows = [['upz_codigo', 'upz', 'corriente', 'vehiculo', 'desde', 'hasta', 'contenedores']]
  for (const sector of plan.sectors) {
    for (const pass of sector.passes) {
      rows.push([
        sector.ref,
        sector.name,
        STREAM_NAMES[pass.stream],
        pass.route,
        clock(pass.fromSeconds),
        clock(pass.toSeconds),
        String(pass.containers),
      ])
    }
  }
  return rows.map((row) => row.map((cell) => (/[",\n]/.test(cell) ? `"${cell.replaceAll('"', '""')}"` : cell)).join(',')).join('\n') + '\n'
}
