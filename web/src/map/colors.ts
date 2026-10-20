import type { WasteStream } from '../demo/plan'

export type RGB = [number, number, number]

/**
 * deck.gl pide colores como [r, g, b] y no lee variables CSS. Se leen de tokens.css al arrancar,
 * así los tokens siguen siendo la única fuente de verdad del color.
 */
function token(name: string): RGB {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const match = /^#([0-9a-f]{6})$/i.exec(value)
  if (!match) {
    throw new Error(`El token ${name} debe ser #rrggbb en tokens.css; vale "${value}"`)
  }
  const n = Number.parseInt(match[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

export function readPalette() {
  return {
    bg: token('--bg'),
    fg: token('--fg'),
    fgMuted: token('--fg-muted'),
    ok: token('--ok'),
    warn: token('--warn'),
    crit: token('--crit'),
    stream: {
      RECYCLABLE: token('--stream-recyclable'),
      ORGANIC: token('--stream-organic'),
      NON_RECYCLABLE: token('--stream-non-recyclable'),
    } satisfies Record<WasteStream, RGB>,
    nonRecyclableEdge: token('--stream-non-recyclable-edge'),
  }
}

export type Palette = ReturnType<typeof readPalette>

/** Umbrales de llenado (simulados) para el borde del contenedor. */
export const FILL_WARN = 0.6
export const FILL_CRIT = 0.85

export function fillColor(palette: Palette, fill: number): RGB {
  if (fill >= FILL_CRIT) return palette.crit
  if (fill >= FILL_WARN) return palette.warn
  return palette.ok
}

/** Color visible del trazo: el negro oficial no se lee sobre el mapa, su trazo usa el borde claro. */
export function strokeColor(palette: Palette, stream: WasteStream): RGB {
  return stream === 'NON_RECYCLABLE' ? palette.nonRecyclableEdge : palette.stream[stream]
}
