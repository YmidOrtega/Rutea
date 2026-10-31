export interface LiveKey {
  locality: number
  slot: Slot
}

export type Slot = 'LMVDia' | 'LMVNoche' | 'MJSDia' | 'MJSNoche'

export const SLOTS: { slot: Slot; label: string; title: string }[] = [
  { slot: 'LMVDia', label: 'LMV día', title: 'Lunes, miércoles y viernes, turno de día' },
  { slot: 'LMVNoche', label: 'LMV noche', title: 'Lunes, miércoles y viernes, turno de noche' },
  { slot: 'MJSDia', label: 'MJS día', title: 'Martes, jueves y sábado, turno de día' },
  { slot: 'MJSNoche', label: 'MJS noche', title: 'Martes, jueves y sábado, turno de noche' },
]

export const FIRST_KEY: LiveKey = { locality: 11, slot: 'LMVNoche' }

export const liveApi = (key: LiveKey) => `/api/live/${key.locality}/${key.slot}`

export const sameKey = (a: LiveKey, b: LiveKey) => a.locality === b.locality && a.slot === b.slot

export function keyFromHash(hash = window.location.hash): LiveKey | null {
  const match = /^#vivo=(\d{1,2})-(LMVDia|LMVNoche|MJSDia|MJSNoche)$/.exec(hash)
  if (!match) return null
  const locality = Number(match[1])
  return locality >= 1 && locality <= 20 ? { locality, slot: match[2] as Slot } : null
}

export const hashOfKey = (key: LiveKey) => `#vivo=${key.locality}-${key.slot}`
